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
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportGraph;
import com.fiskerz.sponsor_system.server.ProfileLookup;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /sponsorship ...} — the operator toolkit, gated at permission level 3.
 *
 * <p>These are the commands that let an admin act on the graph as a whole, and the migration path for a server that
 * had a whitelist before it had this mod.
 */
public final class AdminCommands {
    /** Cap on entries listed by /sponsorship debug, so a large graph does not flood the chat or console. */
    private static final int DEBUG_ENTRY_LIMIT = 30;

    private AdminCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sponsorship")
                .requires(source -> source.hasPermission(SponsorCommands.ADMIN_LEVEL))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("debug")
                        .executes(context -> debug(context.getSource())))
                .then(Commands.literal("stats")
                        .executes(context -> stats(context.getSource())))
                .then(Commands.literal("adopt")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SponsorCommands.KNOWN_NAMES)
                                .executes(context -> adopt(context.getSource(),
                                        StringArgumentType.getString(context, "player"), null))
                                .then(Commands.argument("supporter", StringArgumentType.word())
                                        .suggests(SponsorCommands.KNOWN_NAMES)
                                        .executes(context -> adopt(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "supporter"))))))
                .then(Commands.literal("reassign")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SponsorCommands.KNOWN_NAMES)
                                .then(Commands.argument("newSupporter", StringArgumentType.word())
                                        .suggests(SponsorCommands.KNOWN_NAMES)
                                        .executes(context -> reassign(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "newSupporter"))))))
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
            source.sendSuccess(() -> Messages.success("sponsorsystem.admin.reload.success",
                    manager.graph().size(), manager.graph().edgeCount()), true);
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
     * Brings a player into the graph, as a root or backed by an existing player. This is the migration path for
     * whitelist entries that predate the mod.
     */
    private static int adopt(CommandSourceStack source, String playerName, String supporterName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }

        UUID supporterId = null;
        if (supporterName != null) {
            SponsorEntry supporter = SponsorCommands.lookup(source, manager, supporterName);
            if (supporter == null) {
                return 0;
            }
            supporterId = supporter.getUuid();
        }

        // Prefer the profile already sitting in whitelist.json: no network call, and it works in offline mode.
        Optional<GameProfile> whitelisted = manager.whitelist().findByName(playerName);
        if (whitelisted.isPresent()) {
            finishAdopt(source, manager, whitelisted.get(), supporterId);
            return Command.SINGLE_SUCCESS;
        }

        UUID resolvedSupporter = supporterId;
        source.sendSuccess(() -> Messages.info("sponsorsystem.invite.looking_up", Messages.name(playerName)), false);
        manager.resolveProfile(playerName, lookup -> {
            if (SponsorManager.get() != manager) {
                return;
            }
            if (!lookup.isFound()) {
                source.sendFailure(Messages.lookupFailure(lookup, playerName));
                return;
            }
            finishAdopt(source, manager, lookup.profile(), resolvedSupporter);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void finishAdopt(CommandSourceStack source, SponsorManager manager, GameProfile profile, UUID supporterId) {
        // Adopting is for players the graph has never heard of. Moving someone who is already in it is a
        // reassignment, and saying so is better than silently rewriting their support.
        boolean alreadyLive = manager.graph().get(profile.getId())
                .map(entry -> entry.getStatus().isLive())
                .orElse(false);
        if (alreadyLive) {
            source.sendFailure(Messages.error("sponsorsystem.admin.error.already", Messages.name(profile.getName())));
            return;
        }

        try {
            // Adopted players are treated as established rather than pending: they were already on this server.
            Optional<SponsorEntry> adopted = supporterId == null
                    ? manager.commitAddRoot(profile, SponsorStatus.ACTIVE)
                    : manager.commitAdoptUnder(profile, supporterId, SponsorStatus.ACTIVE);
            if (adopted.isEmpty()) {
                source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
                return;
            }
            if (supporterId == null) {
                source.sendSuccess(() -> Messages.success("sponsorsystem.admin.adopt.root",
                        Messages.name(profile.getName())), true);
            } else {
                String name = manager.graph().get(supporterId).map(SponsorEntry::displayName)
                        .orElse(supporterId.toString());
                source.sendSuccess(() -> Messages.success("sponsorsystem.admin.adopt.under",
                        Messages.name(profile.getName()), Messages.name(name)), true);
            }
            Sponsorsystem.LOGGER.info("Adopted {} ({}) into the support graph under {}.",
                    profile.getName(), profile.getId(), supporterId == null ? "no supporter (root)" : supporterId);
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.refusal(manager, exception, profile.getName()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reassign
    // ---------------------------------------------------------------------------------------------------------------

    private static int reassign(CommandSourceStack source, String playerName, String newSupporterName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry target = SponsorCommands.lookup(source, manager, playerName);
        if (target == null) {
            return 0;
        }
        SponsorEntry newSupporter = SponsorCommands.lookup(source, manager, newSupporterName);
        if (newSupporter == null) {
            return 0;
        }

        try {
            manager.graph().reassignPrimary(target.getUuid(), newSupporter.getUuid(), System.currentTimeMillis());
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.refusal(manager, exception, target.displayName()));
            return 0;
        }
        if (manager.commitRecompute().isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        source.sendSuccess(() -> Messages.success("sponsorsystem.admin.reassign.success",
                Messages.name(target), Messages.name(newSupporter)), true);
        Sponsorsystem.LOGGER.info("Reassigned {} to be supported by {}.",
                target.displayName(), newSupporter.displayName());
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // revoke
    // ---------------------------------------------------------------------------------------------------------------

    /** Force-removes a player: drops every edge into them, unwhitelists and kicks them. */
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
        Optional<SupportGraph.SupportChange> change;
        try {
            change = manager.commitRevoke(target.getUuid(), kickReason);
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.refusal(manager, exception, target.displayName()));
            return 0;
        }
        if (change.isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        int stranded = change.get().abandoned().size();
        source.sendSuccess(() -> Messages.success("sponsorsystem.admin.revoke.success", Messages.name(target)), true);
        if (stranded > 0) {
            // Worth stating plainly: revoking one player near the root can strand a whole branch at once.
            source.sendSuccess(() -> Messages.info("sponsorsystem.admin.revoke.stranded", stranded), false);
        }
        SponsorCommands.announce(manager, Messages.info("sponsorsystem.admin.revoke.announce", Messages.name(target)));
        Sponsorsystem.LOGGER.info("Operator revoked {}; {} player(s) lost support as a result.",
                target.displayName(), stranded);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // debug
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Dumps the mod's entire internal state: bootstrap state, whitelist synchronisation, config in effect, and the
     * graph itself. Intended for diagnosing a server you cannot easily attach a debugger to.
     */
    private static int debug(CommandSourceStack source) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SupportGraph graph = manager.graph();
        MinecraftServer server = manager.server();

        source.sendSuccess(() -> Messages.header("sponsorsystem.debug.header"), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.state",
                manager.state().name(), String.valueOf(Config.BOOTSTRAP_ENABLED.get())), false);

        List<GameProfile> whitelisted = manager.whitelist().entries();
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.whitelist",
                String.valueOf(manager.whitelist().isEnforced()),
                String.valueOf(server.isEnforceWhitelist()),
                whitelisted.size()), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.server",
                String.valueOf(server.usesAuthentication()), manager.storePath().toString()), false);

        Set<UUID> whitelistIds = new HashSet<>();
        whitelisted.forEach(profile -> whitelistIds.add(profile.getId()));
        int missing = 0;
        int stale = 0;
        int active = 0;
        int pending = 0;
        int revoked = 0;
        int abandoned = 0;
        for (SponsorEntry entry : graph.entries()) {
            boolean onList = whitelistIds.contains(entry.getUuid());
            if (entry.getStatus().isLive() && !onList) {
                missing++;
            } else if (!entry.getStatus().isLive() && onList) {
                stale++;
            }
            switch (entry.getStatus()) {
                case ACTIVE -> active++;
                case PENDING -> pending++;
                case REVOKED -> revoked++;
                case ABANDONED -> abandoned++;
            }
        }
        long orphans = whitelisted.stream().filter(profile -> !graph.contains(profile.getId())).count();

        // The counters above are mutated in the loop, so the lambdas below need effectively final copies.
        int totalEntries = graph.size();
        int edgeCount = graph.edgeCount();
        int rootCount = graph.roots().size();
        int activeCount = active;
        int pendingCount = pending;
        int revokedCount = revoked;
        int abandonedCount = abandoned;
        int missingCount = missing;
        int staleCount = stale;
        long orphanCount = orphans;
        int supportedCount = graph.computeSupported(manager.supportModel()).size();

        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.graph",
                totalEntries, edgeCount, rootCount, graph.deepestChain()), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.status",
                activeCount, pendingCount, abandonedCount, revokedCount), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.support",
                manager.supportModel().name(), supportedCount), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.sync",
                missingCount, staleCount, orphanCount), false);

        source.sendSuccess(() -> Messages.header("sponsorsystem.debug.config_header"), false);
        configLine(source, "forceWhitelistOn", Config.FORCE_WHITELIST_ON.get());
        configLine(source, "supportModel", Config.SUPPORT_MODEL.get());
        configLine(source, "sponsorshipMinDurationMinutes", Config.SPONSORSHIP_MIN_DURATION_MINUTES.get());
        configLine(source, "maxSupportTicketsPerPlayer", Config.MAX_SUPPORT_TICKETS_PER_PLAYER.get());
        configLine(source, "separateInviteAndSponsorBudgets", Config.SEPARATE_INVITE_AND_SPONSOR_BUDGETS.get());
        configLine(source, "maxInvitesPerPlayer", Config.MAX_INVITES_PER_PLAYER.get());
        configLine(source, "maxSponsorshipsPerPlayer", Config.MAX_SPONSORSHIPS_PER_PLAYER.get());
        configLine(source, "unlimitedTicketsPermissionLevel", Config.UNLIMITED_TICKETS_PERMISSION_LEVEL.get());
        configLine(source, "minPlaytimeMinutesToInvite", Config.MIN_PLAYTIME_MINUTES_TO_INVITE.get());
        configLine(source, "inviteCooldownMinutes", Config.INVITE_COOLDOWN_MINUTES.get());
        configLine(source, "pendingInviteExpiryHours", Config.PENDING_INVITE_EXPIRY_HOURS.get());
        configLine(source, "announceInvites", Config.ANNOUNCE_INVITES.get());
        configLine(source, "allowOfflineModeUuids", Config.ALLOW_OFFLINE_MODE_UUIDS.get());
        configLine(source, "bootstrap.enabled", Config.BOOTSTRAP_ENABLED.get());
        configLine(source, "bootstrap.restrictToName", "\"" + Config.BOOTSTRAP_RESTRICT_TO_NAME.get() + "\"");
        configLine(source, "bootstrap.restrictToLoopback", Config.BOOTSTRAP_RESTRICT_TO_LOOPBACK.get());
        configLine(source, "bootstrap.opFounder", Config.BOOTSTRAP_OP_FOUNDER.get());

        source.sendSuccess(() -> Messages.header("sponsorsystem.debug.entries_header", totalEntries), false);
        int shown = 0;
        for (SponsorEntry entry : graph.entries()) {
            if (shown++ >= DEBUG_ENTRY_LIMIT) {
                break;
            }
            String supporters = graph.edgesTo(entry.getUuid()).isEmpty()
                    ? "-"
                    : String.join(", ", graph.edgesTo(entry.getUuid()).stream()
                            .map(edge -> graph.get(edge.from()).map(SponsorEntry::displayName)
                                    .orElse(Messages.shortUuid(edge.from()))
                                    + (edge.isPrimary() ? "*" : ""))
                            .toList());
            boolean onList = whitelistIds.contains(entry.getUuid());
            source.sendSuccess(() -> Messages.info("sponsorsystem.debug.entry",
                    entry.displayName(), Messages.shortUuid(entry.getUuid()),
                    entry.isRoot() ? "root" : supporters,
                    entry.getStatus().name(), String.valueOf(onList)), false);
        }
        if (totalEntries > DEBUG_ENTRY_LIMIT) {
            source.sendSuccess(() -> Messages.info("sponsorsystem.debug.truncated",
                    totalEntries - DEBUG_ENTRY_LIMIT), false);
        }
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.edges_legend"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void configLine(CommandSourceStack source, String key, Object value) {
        source.sendSuccess(() -> Messages.info("sponsorsystem.debug.config_line", key, String.valueOf(value)), false);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // stats
    // ---------------------------------------------------------------------------------------------------------------

    private static int stats(CommandSourceStack source) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SupportGraph graph = manager.graph();

        int active = 0;
        int pending = 0;
        int revoked = 0;
        int abandoned = 0;
        for (SponsorEntry entry : graph.entries()) {
            switch (entry.getStatus()) {
                case ACTIVE -> active++;
                case PENDING -> pending++;
                case REVOKED -> revoked++;
                case ABANDONED -> abandoned++;
            }
        }

        Set<UUID> inGraph = new HashSet<>();
        graph.entries().forEach(entry -> inGraph.add(entry.getUuid()));
        List<GameProfile> whitelist = manager.whitelist().entries();
        long orphans = whitelist.stream().filter(profile -> !inGraph.contains(profile.getId())).count();

        int total = graph.size();
        int edges = graph.edgeCount();
        int roots = graph.roots().size();
        int deepest = graph.deepestChain();
        int activeCount = active;
        int pendingCount = pending;
        int revokedCount = revoked;
        int abandonedCount = abandoned;
        long orphanCount = orphans;
        long sponsorships = graph.allEdges().stream().filter(edge -> !edge.isPrimary()).count();

        source.sendSuccess(() -> Messages.header("sponsorsystem.admin.stats.header"), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.total", total, roots), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.edges", edges, sponsorships), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.admin.stats.status",
                activeCount, pendingCount, abandonedCount, revokedCount), false);
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
}
