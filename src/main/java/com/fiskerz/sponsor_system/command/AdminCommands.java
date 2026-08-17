package com.fiskerz.sponsor_system.command;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraph;
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

/**
 * {@code /sponsorship ...} — the operator toolkit, gated at permission level 3.
 *
 * <p>These are the commands that let an admin act on a whole branch at once, and the migration path for a server that
 * had a whitelist before it had this mod.
 */
public final class AdminCommands {
    private AdminCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sponsorship")
                .requires(source -> source.hasPermission(SponsorCommands.ADMIN_LEVEL))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("stats")
                        .executes(context -> stats(context.getSource())))
                .then(Commands.literal("adopt")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SponsorCommands.KNOWN_NAMES)
                                .executes(context -> adopt(context.getSource(),
                                        StringArgumentType.getString(context, "player"), null))
                                .then(Commands.argument("sponsor", StringArgumentType.word())
                                        .suggests(SponsorCommands.KNOWN_NAMES)
                                        .executes(context -> adopt(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "sponsor"))))))
                .then(Commands.literal("reassign")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SponsorCommands.KNOWN_NAMES)
                                .then(Commands.argument("newSponsor", StringArgumentType.word())
                                        .suggests(SponsorCommands.KNOWN_NAMES)
                                        .executes(context -> reassign(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "newSponsor"))))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SponsorCommands.KNOWN_NAMES)
                                .executes(context -> revoke(context.getSource(),
                                        StringArgumentType.getString(context, "player"))))));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reload
    // ---------------------------------------------------------------------------------------------------------------

    private static int reload(CommandSourceStack source) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        try {
            List<String> warnings = manager.reload();
            source.sendSuccess(() -> Messages.success("sponsorsystem.admin.reload.success", manager.graph().size()), true);
            if (!warnings.isEmpty()) {
                source.sendSuccess(() -> Messages.info("sponsorsystem.admin.reload.warnings", warnings.size()), false);
                warnings.forEach(Sponsorsystem.LOGGER::warn);
            }
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            Sponsorsystem.LOGGER.error("Reloading sponsors.json failed.", exception);
            source.sendFailure(Messages.error("sponsorsystem.admin.reload.failed"));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // adopt
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Brings a player into the tree, as a root or under an existing sponsor. This is the migration path for whitelist
     * entries that predate the mod.
     */
    private static int adopt(CommandSourceStack source, String playerName, String sponsorName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }

        UUID sponsorId = null;
        if (sponsorName != null) {
            SponsorEntry sponsor = SponsorCommands.lookup(source, manager, sponsorName);
            if (sponsor == null) {
                return 0;
            }
            sponsorId = sponsor.getUuid();
        }

        // Prefer the profile already sitting in whitelist.json: no network call, and it works in offline mode.
        Optional<GameProfile> whitelisted = manager.whitelist().findByName(playerName);
        if (whitelisted.isPresent()) {
            finishAdopt(source, manager, whitelisted.get(), sponsorId);
            return Command.SINGLE_SUCCESS;
        }

        UUID resolvedSponsor = sponsorId;
        source.sendSuccess(() -> Messages.info("sponsorsystem.invite.looking_up", Messages.name(playerName)), false);
        manager.resolveProfile(playerName, lookup -> {
            if (SponsorManager.get() != manager) {
                return;
            }
            if (!lookup.isFound()) {
                source.sendFailure(adoptLookupFailure(lookup, playerName));
                return;
            }
            finishAdopt(source, manager, lookup.profile(), resolvedSponsor);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void finishAdopt(CommandSourceStack source, SponsorManager manager, GameProfile profile, UUID sponsorId) {
        // Adopting is for players the tree has never heard of. Moving someone who is already in it is a reassignment,
        // and saying so is better than silently re-parenting a branch.
        boolean alreadyLive = manager.graph().get(profile.getId())
                .map(entry -> entry.getStatus().isLive())
                .orElse(false);
        if (alreadyLive) {
            source.sendFailure(Messages.error("sponsorsystem.admin.error.already", Messages.name(profile.getName())));
            return;
        }

        try {
            // Adopted players are treated as established rather than pending: they were already on this server.
            Optional<SponsorEntry> adopted = manager.commitAdopt(profile, sponsorId, SponsorStatus.ACTIVE);
            if (adopted.isEmpty()) {
                source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
                return;
            }
            if (sponsorId == null) {
                source.sendSuccess(() -> Messages.success("sponsorsystem.admin.adopt.root", Messages.name(profile.getName())), true);
            } else {
                String sponsorName = manager.graph().get(sponsorId).map(SponsorEntry::displayName).orElse(sponsorId.toString());
                source.sendSuccess(() -> Messages.success("sponsorsystem.admin.adopt.under",
                        Messages.name(profile.getName()), Messages.name(sponsorName)), true);
            }
            Sponsorsystem.LOGGER.info("Adopted {} ({}) into the sponsorship tree under {}.",
                    profile.getName(), profile.getId(), sponsorId == null ? "no sponsor (root)" : sponsorId);
        } catch (SponsorGraphException exception) {
            source.sendFailure(adminRefusal(exception, profile.getName()));
        }
    }

    private static Component adoptLookupFailure(ProfileLookup lookup, String name) {
        return switch (lookup.result()) {
            case INVALID_NAME -> Messages.error("sponsorsystem.error.lookup.invalid_name", Messages.name(name));
            case NOT_FOUND -> Messages.error("sponsorsystem.error.lookup.not_found", Messages.name(name));
            case OFFLINE_MODE_BLOCKED -> Messages.error("sponsorsystem.error.lookup.offline_mode");
            default -> Messages.error("sponsorsystem.error.lookup.unavailable", Messages.name(name));
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reassign
    // ---------------------------------------------------------------------------------------------------------------

    private static int reassign(CommandSourceStack source, String playerName, String newSponsorName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry target = SponsorCommands.lookup(source, manager, playerName);
        if (target == null) {
            return 0;
        }
        SponsorEntry newSponsor = SponsorCommands.lookup(source, manager, newSponsorName);
        if (newSponsor == null) {
            return 0;
        }

        try {
            manager.graph().reassign(target.getUuid(), newSponsor.getUuid());
        } catch (SponsorGraphException exception) {
            source.sendFailure(adminRefusal(exception, target.displayName()));
            return 0;
        }
        if (!manager.save()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        source.sendSuccess(() -> Messages.success("sponsorsystem.admin.reassign.success",
                Messages.name(target), Messages.name(newSponsor)), true);
        Sponsorsystem.LOGGER.info("Reassigned {} to sponsor {}.", target.displayName(), newSponsor.displayName());
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // revoke
    // ---------------------------------------------------------------------------------------------------------------

    /** Force-revokes regardless of who sponsored the target. */
    private static int revoke(CommandSourceStack source, String playerName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry target = SponsorCommands.lookup(source, manager, playerName);
        if (target == null) {
            return 0;
        }

        Component kickReason = Component.translatable("sponsorsystem.kick.revoked_by_admin", target.displayName());
        boolean cascade = Config.CASCADE_ON_REVOKE.get();
        Optional<List<SponsorEntry>> revoked;
        try {
            revoked = manager.commitRevoke(target.getUuid(), cascade, kickReason);
        } catch (SponsorGraphException exception) {
            source.sendFailure(adminRefusal(exception, target.displayName()));
            return 0;
        }
        if (revoked.isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        int count = revoked.get().size();
        source.sendSuccess(() -> Messages.success("sponsorsystem.admin.revoke.success", Messages.name(target), count), true);
        SponsorCommands.announce(manager, Messages.info("sponsorsystem.admin.revoke.announce", Messages.name(target)));
        Sponsorsystem.LOGGER.info("Operator revoked {}; {} player(s) removed from the whitelist.",
                target.displayName(), count);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // stats
    // ---------------------------------------------------------------------------------------------------------------

    private static int stats(CommandSourceStack source) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorGraph graph = manager.graph();

        int active = 0;
        int pending = 0;
        int revoked = 0;
        for (SponsorEntry entry : graph.entries()) {
            switch (entry.getStatus()) {
                case ACTIVE -> active++;
                case PENDING -> pending++;
                case REVOKED -> revoked++;
            }
        }

        Set<UUID> inGraph = new HashSet<>();
        graph.entries().forEach(entry -> inGraph.add(entry.getUuid()));
        List<GameProfile> whitelist = manager.whitelist().entries();
        long orphans = whitelist.stream().filter(profile -> !inGraph.contains(profile.getId())).count();

        int total = graph.size();
        int roots = graph.roots().size();
        int deepest = graph.deepestChain();
        long orphanCount = orphans;
        // The counters above are mutated in the loop, so the lambdas below need effectively final copies.
        int activeCount = active;
        int pendingCount = pending;
        int revokedCount = revoked;

        source.sendSuccess(() -> Messages.header("sponsorsystem.admin.stats.header"), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.total", total, roots), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.status", activeCount, pendingCount, revokedCount), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.depth", deepest), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.whitelist", whitelist.size(), orphanCount), false);
        if (orphanCount > 0) {
            source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.orphan_hint"), false);
        }
        if (!manager.whitelist().isEnforced()) {
            source.sendSuccess(() -> Messages.error("sponsorsystem.admin.stats.whitelist_off"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------------------------

    private static Component adminRefusal(SponsorGraphException exception, String targetName) {
        return switch (exception.getReason()) {
            case WOULD_CREATE_CYCLE -> Messages.error("sponsorsystem.admin.error.cycle", Messages.name(targetName));
            case SELF_INVITE -> Messages.error("sponsorsystem.invite.self");
            case ALREADY_SPONSORED -> Messages.error("sponsorsystem.admin.error.already", Messages.name(targetName));
            case INVITE_LIMIT_REACHED -> Messages.error("sponsorsystem.invite.limit", Config.MAX_INVITES_PER_PLAYER.get());
            case SPONSOR_REVOKED -> Messages.error("sponsorsystem.admin.error.sponsor_revoked", Messages.name(targetName));
            case NOT_IN_GRAPH, SPONSOR_NOT_IN_GRAPH -> Messages.error("sponsorsystem.error.not_in_tree", Messages.name(targetName));
        };
    }
}
