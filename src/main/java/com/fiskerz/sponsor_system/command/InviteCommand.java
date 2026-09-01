package com.fiskerz.sponsor_system.command;

import java.util.UUID;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.TicketBudget;
import com.fiskerz.sponsor_system.server.ProfileLookup;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /invite <name>} — bring someone new onto the server and back them.
 *
 * <p>Level 0: no {@code requires} gate at all. This creates the one PRIMARY edge that whitelists a player, so it is
 * only legal for someone who is not in the graph yet; adding support for someone already here is {@code /sponsor}.
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
                source.sendFailure(Messages.error("sponsorsystem.invite.not_in_graph"));
                return 0;
            }
            // An operator who is not yet in the graph is adopted as a root, so a graph that lost its root can grow a
            // new branch from in-game. This must not fire during the founder-bootstrap window: the whitelist is off
            // there, so anyone at all could be online, and this would hand them a root bypassing every guard.
            if (manager.isBootstrapping()) {
                source.sendFailure(Messages.error("sponsorsystem.invite.bootstrap_in_progress"));
                return 0;
            }
            if (manager.commitAddRoot(caller.getGameProfile(), SponsorStatus.ACTIVE).isEmpty()) {
                source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
                return 0;
            }
            Sponsorsystem.LOGGER.info("{} was adopted as a root of the support graph by running /invite as an operator.",
                    caller.getGameProfile().getName());
            source.sendSuccess(() -> Messages.info("sponsorsystem.invite.bootstrapped"), false);
        }

        // Operators are exempt from the rate limits, per the config documentation.
        if (!isOperator && !passesLimits(source, manager, caller)) {
            return 0;
        }

        TicketBudget budget = manager.budgetFor(callerId, EdgeKind.PRIMARY);
        if (!budget.canSpend()) {
            source.sendFailure(Messages.error("sponsorsystem.tickets.none_left", budget.limit()));
            return 0;
        }

        source.sendSuccess(() -> Messages.info("sponsorsystem.invite.looking_up", Messages.name(targetName)), false);
        // The lookup may hit Mojang's API, so it runs off-thread; the callback is already back on the server thread.
        manager.resolveProfile(targetName, lookup -> complete(manager, callerId, targetName, lookup));
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
                long remaining = last + cooldownMinutes * 60_000L - System.currentTimeMillis();
                if (remaining > 0L) {
                    // Round up, so "1 minute left" never reads as "0 minutes left".
                    source.sendFailure(Messages.error("sponsorsystem.invite.cooldown", (remaining + 59_999L) / 60_000L));
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
    private static void complete(SponsorManager manager, UUID callerId, String targetName, ProfileLookup lookup) {
        if (SponsorManager.get() != manager) {
            return; // The server stopped, or restarted, while the lookup was in flight.
        }
        if (!lookup.isFound()) {
            manager.tell(callerId, Messages.lookupFailure(lookup, targetName));
            return;
        }

        GameProfile profile = lookup.profile();
        TicketBudget budget = manager.budgetFor(callerId, EdgeKind.PRIMARY);
        try {
            java.util.Optional<SupportEdge> invited = manager.commitInvite(callerId, profile, budget.spendable());
            if (invited.isEmpty()) {
                manager.tell(callerId, Messages.error("sponsorsystem.error.save_failed"));
                return;
            }

            manager.tell(callerId, Messages.success("sponsorsystem.invite.success", Messages.name(profile.getName())));
            manager.tell(callerId, Messages.ticketsRemaining(manager.budgetFor(callerId, EdgeKind.PRIMARY)));

            String supporterName = manager.graph().get(callerId).map(SponsorEntry::displayName).orElse(profile.getName());
            SponsorCommands.announce(manager, Messages.info("sponsorsystem.invite.announce",
                    Messages.name(supporterName), Messages.name(profile.getName())));
            Sponsorsystem.LOGGER.info("{} invited {} ({})", supporterName, profile.getName(), profile.getId());
        } catch (com.fiskerz.sponsor_system.graph.SponsorGraphException exception) {
            manager.tell(callerId, Messages.refusal(manager, exception, profile.getName()));
        }
    }
}
