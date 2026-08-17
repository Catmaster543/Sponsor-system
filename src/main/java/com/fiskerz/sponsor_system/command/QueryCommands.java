package com.fiskerz.sponsor_system.command;

import java.util.List;
import java.util.UUID;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraph;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * The read-only commands: {@code /sponsor}, {@code /invites} and {@code /invitetree}.
 *
 * <p>All three are level 0. {@code /sponsor} is always public — a visible chain of responsibility is the whole point
 * of the system — while {@code /invites} and {@code /invitetree} respect the {@code publicInviteTree} setting when
 * someone asks about a player other than themselves.
 */
public final class QueryCommands {
    /** Trees can get large; past this many lines the output is truncated with a count of what was left out. */
    private static final int MAX_TREE_LINES = 200;

    private QueryCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sponsor")
                .executes(context -> sponsor(context.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> sponsor(context.getSource(), StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("invites")
                .executes(context -> invites(context.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> invites(context.getSource(), StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("invitetree")
                .executes(context -> invitetree(context.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> invitetree(context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // /sponsor
    // ---------------------------------------------------------------------------------------------------------------

    private static int sponsor(CommandSourceStack source, String name) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry subject = resolveSubject(source, manager, name);
        if (subject == null) {
            return 0;
        }

        source.sendSuccess(() -> Messages.header("sponsorsystem.sponsor.header", Messages.name(subject)), false);
        source.sendSuccess(() -> Messages.info("sponsorsystem.sponsor.status", Messages.status(subject.getStatus())), false);

        SponsorGraph graph = manager.graph();
        if (subject.isRoot()) {
            source.sendSuccess(() -> Messages.info("sponsorsystem.sponsor.is_root"), false);
            return Command.SINGLE_SUCCESS;
        }

        String sponsorName = graph.get(subject.getSponsor()).map(SponsorEntry::displayName)
                .orElse(subject.getSponsor().toString());
        source.sendSuccess(() -> Messages.info("sponsorsystem.sponsor.sponsored_by", Messages.name(sponsorName)), false);

        // chainToRoot starts with the subject; the chain of responsibility above them is everything after that.
        List<SponsorEntry> chain = graph.chainToRoot(subject.getUuid());
        MutableComponent rendered = Component.empty();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                rendered.append(Component.literal(" <- ").withStyle(ChatFormatting.DARK_GRAY));
            }
            rendered.append(Messages.name(chain.get(i)));
        }
        source.sendSuccess(() -> Messages.info("sponsorsystem.sponsor.chain", rendered), false);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // /invites
    // ---------------------------------------------------------------------------------------------------------------

    private static int invites(CommandSourceStack source, String name) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry subject = resolveSubject(source, manager, name);
        if (subject == null) {
            return 0;
        }
        if (!SponsorCommands.mayInspect(source, subject.getUuid())) {
            source.sendFailure(Messages.error("sponsorsystem.error.private"));
            return 0;
        }

        SponsorGraph graph = manager.graph();
        List<UUID> children = graph.directChildren(subject.getUuid());
        if (children.isEmpty()) {
            source.sendSuccess(() -> Messages.info("sponsorsystem.invites.none", Messages.name(subject)), false);
        } else {
            source.sendSuccess(() -> Messages.header("sponsorsystem.invites.header", Messages.name(subject), children.size()), false);
            for (UUID child : children) {
                graph.get(child).ifPresent(entry -> source.sendSuccess(
                        () -> Messages.info("sponsorsystem.invites.entry", Messages.name(entry), Messages.status(entry.getStatus())),
                        false));
            }
        }

        int max = Config.MAX_INVITES_PER_PLAYER.get();
        if (max < 0) {
            source.sendSuccess(() -> Messages.info("sponsorsystem.invites.slots_unlimited"), false);
        } else {
            int remaining = Math.max(0, max - graph.liveInviteCount(subject.getUuid()));
            source.sendSuccess(() -> Messages.info("sponsorsystem.invites.slots", remaining, max), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // /invitetree
    // ---------------------------------------------------------------------------------------------------------------

    private static int invitetree(CommandSourceStack source, String name) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry subject = resolveSubject(source, manager, name);
        if (subject == null) {
            return 0;
        }
        if (!SponsorCommands.mayInspect(source, subject.getUuid())) {
            source.sendFailure(Messages.error("sponsorsystem.error.private"));
            return 0;
        }

        source.sendSuccess(() -> Messages.header("sponsorsystem.tree.header", Messages.name(subject)), false);
        int[] budget = {MAX_TREE_LINES};
        int printed = renderBranch(source, manager.graph(), subject.getUuid(), 0, budget);
        if (budget[0] <= 0) {
            int remaining = manager.graph().subtreeOf(subject.getUuid()).size() - printed;
            if (remaining > 0) {
                source.sendSuccess(() -> Messages.info("sponsorsystem.tree.truncated", remaining), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Prints one node and recurses into its invitees, indenting by depth.
     *
     * @param budget single-element array used as a shared line counter across the recursion
     * @return how many lines were printed
     */
    private static int renderBranch(CommandSourceStack source, SponsorGraph graph, UUID uuid, int depth, int[] budget) {
        if (budget[0] <= 0) {
            return 0;
        }
        SponsorEntry entry = graph.get(uuid).orElse(null);
        if (entry == null) {
            return 0;
        }

        budget[0]--;
        String indent = depth == 0 ? "" : "  ".repeat(depth - 1) + "└ ";
        MutableComponent line = Component.literal(indent).withStyle(ChatFormatting.DARK_GRAY)
                .append(Messages.name(entry))
                .append(Component.literal(" "))
                .append(Messages.status(entry.getStatus()));
        source.sendSuccess(() -> line, false);

        int printed = 1;
        for (UUID child : graph.directChildren(uuid)) {
            printed += renderBranch(source, graph, child, depth + 1, budget);
        }
        return printed;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Resolves the player a read-only command is about: the named one, or the caller when no name was given.
     * Sends the appropriate failure and returns {@code null} if there is nobody to report on.
     */
    private static SponsorEntry resolveSubject(CommandSourceStack source, SponsorManager manager, String name) {
        if (name != null) {
            return SponsorCommands.lookup(source, manager, name);
        }
        ServerPlayer player = SponsorCommands.player(source);
        if (player == null) {
            return null;
        }
        SponsorEntry entry = manager.graph().get(player.getUUID()).orElse(null);
        if (entry == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.self_not_in_tree"));
        }
        return entry;
    }
}
