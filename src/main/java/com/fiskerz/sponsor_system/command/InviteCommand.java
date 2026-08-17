package com.fiskerz.sponsor_system.command;

import java.util.Optional;
import java.util.UUID;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.server.ProfileLookup;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /invite <name>} — whitelist a player immediately and record the caller as their sponsor.
 *
 * <p>Usable by anyone: no {@code .requires} gate, so the command sits at permission level 0.
 *
 * <p>The username is taken as a raw {@link StringArgumentType#word()} rather than a {@code GameProfileArgument},
 * because that argument type only resolves profiles already in the local cache and fails outright for a player who has
 * never joined — exactly the case this command exists to serve.
 */
public final class InviteCommand {
    private InviteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invite")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> invite(context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    private static int invite(CommandSourceStack source, String targetName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        ServerPlayer caller = SponsorCommands.player(source);
        if (caller == null) {
            return 0;
        }

        UUID callerId = caller.getUUID();
        boolean isOperator = manager.isOperator(caller.getGameProfile());

        if (!manager.graph().contains(callerId)) {
            if (!isOperator) {
                source.sendFailure(Messages.error("sponsorsystem.invite.not_in_tree"));
                return 0;
            }
            // Bootstrapping: operators bypass the whitelist, so the first operator to invite anyone becomes the root
            // of the tree. Without this an empty tree could never grow a first branch from in-game.
            if (manager.commitAdopt(caller.getGameProfile(), null, SponsorStatus.ACTIVE).isEmpty()) {
                source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
                return 0;
            }
            Sponsorsystem.LOGGER.info("{} was adopted as a root of the sponsorship tree by running /invite as an operator.",
                    caller.getGameProfile().getName());
            source.sendSuccess(() -> Messages.info("sponsorsystem.invite.bootstrapped"), false);
        }

        // Operators are exempt from every rate limit, per the config documentation.
        if (!isOperator && !passesLimits(source, manager, caller)) {
            return 0;
        }

        int maxInvites = isOperator ? -1 : Config.MAX_INVITES_PER_PLAYER.get();
        if (maxInvites >= 0 && manager.graph().liveInviteCount(callerId) >= maxInvites) {
            source.sendFailure(Messages.error("sponsorsystem.invite.limit", maxInvites));
            return 0;
        }

        source.sendSuccess(() -> Messages.info("sponsorsystem.invite.looking_up", Messages.name(targetName)), false);
        // The lookup may hit Mojang's API, so it runs off-thread; the callback is already back on the server thread.
        manager.resolveProfile(targetName, lookup -> complete(manager, callerId, targetName, maxInvites, lookup));
        return Command.SINGLE_SUCCESS;
    }

    /** Playtime and cooldown gates. Both are off by default. */
    private static boolean passesLimits(CommandSourceStack source, SponsorManager manager, ServerPlayer caller) {
        int requiredMinutes = Config.MIN_PLAYTIME_MINUTES_TO_INVITE.get();
        if (requiredMinutes > 0) {
            int played = manager.playtimeMinutes(caller);
            if (played < requiredMinutes) {
                source.sendFailure(Messages.error("sponsorsystem.invite.playtime", requiredMinutes, played));
                return false;
            }
        }

        int cooldownMinutes = Config.INVITE_COOLDOWN_MINUTES.get();
        if (cooldownMinutes > 0) {
            long last = manager.graph().lastInviteAt(caller.getUUID());
            if (last > 0L) {
                long readyAt = last + cooldownMinutes * 60_000L;
                long remaining = readyAt - System.currentTimeMillis();
                if (remaining > 0L) {
                    // Round up, so "1 minute left" never reads as "0 minutes left".
                    long minutes = (remaining + 59_999L) / 60_000L;
                    source.sendFailure(Messages.error("sponsorsystem.invite.cooldown", minutes));
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Runs on the server thread once the profile lookup finished. Everything is re-checked here: the world may have
     * moved on while the lookup was in flight.
     */
    private static void complete(SponsorManager manager, UUID callerId, String targetName, int maxInvites, ProfileLookup lookup) {
        if (SponsorManager.get() != manager) {
            return; // The server stopped, or restarted, while the lookup was in flight.
        }

        if (!lookup.isFound()) {
            SponsorCommands.replyIfOnline(manager, callerId, lookupFailure(lookup, targetName));
            return;
        }

        GameProfile profile = lookup.profile();
        try {
            Optional<SponsorEntry> invited = manager.commitInvite(callerId, profile, maxInvites);
            if (invited.isEmpty()) {
                SponsorCommands.replyIfOnline(manager, callerId, Messages.error("sponsorsystem.error.save_failed"));
                return;
            }

            SponsorCommands.replyIfOnline(manager, callerId,
                    Messages.success("sponsorsystem.invite.success", Messages.name(profile.getName())));

            String sponsorName = manager.graph().get(callerId).map(SponsorEntry::displayName).orElse(profile.getName());
            SponsorCommands.announce(manager, Messages.info("sponsorsystem.invite.announce",
                    Messages.name(sponsorName), Messages.name(profile.getName())));
            Sponsorsystem.LOGGER.info("{} invited {} ({})", sponsorName, profile.getName(), profile.getId());
        } catch (SponsorGraphException exception) {
            SponsorCommands.replyIfOnline(manager, callerId, refusal(manager, exception, profile.getName()));
        }
    }

    private static Component lookupFailure(ProfileLookup lookup, String targetName) {
        return switch (lookup.result()) {
            case INVALID_NAME -> Messages.error("sponsorsystem.error.lookup.invalid_name", Messages.name(targetName));
            case NOT_FOUND -> Messages.error("sponsorsystem.error.lookup.not_found", Messages.name(targetName));
            case OFFLINE_MODE_BLOCKED -> Messages.error("sponsorsystem.error.lookup.offline_mode");
            default -> Messages.error("sponsorsystem.error.lookup.unavailable", Messages.name(targetName));
        };
    }

    /** Turns a refusal from the graph into something the player can act on. */
    private static Component refusal(SponsorManager manager, SponsorGraphException exception, String targetName) {
        return switch (exception.getReason()) {
            case SELF_INVITE -> Messages.error("sponsorsystem.invite.self");
            case ALREADY_SPONSORED -> {
                UUID sponsor = exception.getSubject();
                String sponsorName = sponsor == null
                        ? null
                        : manager.graph().get(sponsor).map(SponsorEntry::displayName).orElse(sponsor.toString());
                yield sponsorName == null
                        ? Messages.error("sponsorsystem.invite.already_root", Messages.name(targetName))
                        : Messages.error("sponsorsystem.invite.already", Messages.name(targetName), Messages.name(sponsorName));
            }
            case INVITE_LIMIT_REACHED -> Messages.error("sponsorsystem.invite.limit", Config.MAX_INVITES_PER_PLAYER.get());
            case WOULD_CREATE_CYCLE -> Messages.error("sponsorsystem.invite.cycle", Messages.name(targetName));
            case SPONSOR_NOT_IN_GRAPH -> Messages.error("sponsorsystem.invite.not_in_tree");
            case SPONSOR_REVOKED -> Messages.error("sponsorsystem.invite.self_revoked");
            case NOT_IN_GRAPH -> Messages.error("sponsorsystem.error.not_in_tree", Messages.name(targetName));
        };
    }
}
