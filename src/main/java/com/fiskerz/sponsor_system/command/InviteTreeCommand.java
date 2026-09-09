package com.fiskerz.sponsor_system.command;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportGraph;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /invitetree [player]} — the support graph, drawn.
 *
 * <p>Operator only (level 3). A self-only view for ordinary players is a later round.
 *
 * <p>The graph is a DAG, not a tree: a player can be backed by several people, and can sponsor someone above them.
 * What is drawn is therefore the PRIMARY edges as a skeleton, with every additional supporter listed underneath. A
 * visited set stops the recursion at anyone already drawn, and a back-reference is shown in their place.
 */
public final class InviteTreeCommand {
    /** Past this many lines the output is cut off, so one command cannot flood a chat window. */
    private static final int MAX_LINES = 60;

    private InviteTreeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invitetree")
                .requires(source -> source.hasPermission(SponsorCommands.ADMIN_LEVEL))
                .executes(context -> byName(context.getSource(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> byName(context.getSource(),
                                StringArgumentType.getString(context, "player"))))
                // Every clickable name in the output targets this form. Names change and can be reused; UUIDs do not.
                .then(Commands.literal("uuid")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(context -> byUuid(context.getSource(),
                                        StringArgumentType.getString(context, "uuid"))))));
    }

    private static int byName(CommandSourceStack source, String name) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        SponsorEntry subject;
        if (name != null) {
            subject = SponsorCommands.lookup(source, manager, name);
        } else {
            var player = SponsorCommands.player(source);
            if (player == null) {
                return 0;
            }
            subject = manager.graph().get(player.getUUID()).orElse(null);
            if (subject == null) {
                source.sendFailure(Messages.error("sponsorsystem.error.self_not_in_graph"));
                return 0;
            }
        }
        return subject == null ? 0 : render(source, manager, subject);
    }

    private static int byUuid(CommandSourceStack source, String raw) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Messages.error("sponsorsystem.tree.bad_uuid", Messages.name(raw)));
            return 0;
        }
        SponsorEntry subject = manager.graph().get(uuid).orElse(null);
        if (subject == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.not_in_graph", Messages.name(raw)));
            return 0;
        }
        return render(source, manager, subject);
    }

    /**
     * Builds the whole view as one component with embedded newlines.
     *
     * <p>One message rather than many keeps it visually contiguous in chat, and sending it through
     * {@code sendSuccess} as a system message is what preserves the click and hover events intact.
     */
    private static int render(CommandSourceStack source, SponsorManager manager, SponsorEntry subject) {
        SupportGraph graph = manager.graph();
        // A console, command block or RCON source has no client to handle a click, so it gets plain text. Emitting
        // dead links there would look broken rather than merely plain.
        boolean clickable = source.getPlayer() != null;

        MutableComponent out = Component.empty();
        out.append(Messages.header("sponsorsystem.tree.header", Messages.name(subject)));
        out.append(Component.literal("\n"));
        out.append(Messages.info("sponsorsystem.tree.summary",
                Messages.status(subject.getStatus()),
                graph.descendantsOf(subject.getUuid()).size() - 1,
                graph.edgesFrom(subject.getUuid()).size(),
                graph.edgesTo(subject.getUuid()).size()));

        // --- the line of inviters above them, root first ---------------------------------------------------------
        List<SponsorEntry> chain = graph.inviterChain(subject.getUuid());
        if (chain.size() > 1) {
            MutableComponent breadcrumb = Component.empty();
            for (int i = chain.size() - 1; i >= 0; i--) {
                if (i < chain.size() - 1) {
                    breadcrumb.append(Component.literal(" > ").withStyle(ChatFormatting.DARK_GRAY));
                }
                SponsorEntry link = chain.get(i);
                breadcrumb.append(i == 0
                        ? Component.literal("[" + link.displayName() + "]").withStyle(ChatFormatting.WHITE)
                        : nameComponent(manager, link, clickable));
            }
            out.append(Component.literal("\n"));
            out.append(Messages.info("sponsorsystem.tree.chain", breadcrumb));
        }

        // --- everyone they brought in, and everyone below that ---------------------------------------------------
        out.append(Component.literal("\n"));
        int[] budget = {MAX_LINES};
        Set<UUID> seen = new HashSet<>();
        renderNode(out, manager, subject, "", true, true, seen, budget, clickable);

        if (budget[0] <= 0) {
            out.append(Component.literal("\n"));
            out.append(Messages.info("sponsorsystem.tree.truncated"));
        }
        source.sendSuccess(() -> out, false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Draws one player and recurses into the people they invited.
     *
     * @param prefix   the box-drawing indent accumulated from ancestors
     * @param isLast   whether this node is the last child of its parent, which decides its elbow
     * @param isRoot   whether this is the node the command was run on, which gets no elbow at all
     * @param seen     visited set; a player already drawn gets a back-reference instead of a second subtree
     * @param budget   single-element line counter shared across the whole recursion
     */
    private static void renderNode(MutableComponent out, SponsorManager manager, SponsorEntry entry, String prefix,
            boolean isLast, boolean isRoot, Set<UUID> seen, int[] budget, boolean clickable) {
        if (budget[0] <= 0) {
            return;
        }
        budget[0]--;

        SupportGraph graph = manager.graph();
        String elbow = isRoot ? "" : (isLast ? "└─ " : "├─ ");
        out.append(Component.literal(prefix + elbow).withStyle(ChatFormatting.DARK_GRAY));

        if (!seen.add(entry.getUuid())) {
            // Already drawn somewhere above. Recursing again would loop forever on a sponsored ancestor.
            out.append(nameComponent(manager, entry, clickable));
            out.append(Component.literal(" "));
            out.append(Messages.info("sponsorsystem.tree.already_shown"));
            return;
        }

        out.append(nameComponent(manager, entry, clickable));
        if (entry.getStatus() == SponsorStatus.ABANDONED) {
            out.append(Component.literal(" "));
            out.append(Messages.error("sponsorsystem.tree.abandoned_marker"));
        } else if (entry.getStatus() != SponsorStatus.ACTIVE) {
            out.append(Component.literal(" "));
            out.append(Messages.status(entry.getStatus()));
        }

        // Additional supporters go on their own line, clearly not part of the skeleton, so it stays obvious that the
        // tree lines are invites and these are everybody else holding this player up.
        List<SupportEdge> extra = graph.edgesTo(entry.getUuid()).stream().filter(edge -> !edge.isPrimary()).toList();
        String childPrefix = prefix + (isRoot ? "" : (isLast ? "   " : "│  "));
        if (!extra.isEmpty() && budget[0] > 0) {
            budget[0]--;
            out.append(Component.literal("\n"));
            out.append(Component.literal(childPrefix + "   ").withStyle(ChatFormatting.DARK_GRAY));
            MutableComponent sponsors = Component.empty();
            for (int i = 0; i < extra.size(); i++) {
                if (i > 0) {
                    sponsors.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                }
                SponsorEntry supporter = graph.get(extra.get(i).from()).orElse(null);
                sponsors.append(supporter == null
                        ? Component.literal("?").withStyle(ChatFormatting.DARK_GRAY)
                        : nameComponent(manager, supporter, clickable));
            }
            out.append(Messages.info("sponsorsystem.tree.also_supported_by", sponsors));
        }

        List<SupportEdge> children = graph.edgesFrom(entry.getUuid()).stream().filter(SupportEdge::isPrimary).toList();
        for (int i = 0; i < children.size(); i++) {
            if (budget[0] <= 0) {
                return;
            }
            SponsorEntry child = graph.get(children.get(i).to()).orElse(null);
            if (child == null) {
                continue;
            }
            out.append(Component.literal("\n"));
            renderNode(out, manager, child, childPrefix, i == children.size() - 1, false, seen, budget, clickable);
        }
    }

    /** A name, clickable for a player source and plain for anything else, coloured by status. */
    private static Component nameComponent(SponsorManager manager, SponsorEntry entry, boolean clickable) {
        ChatFormatting colour = switch (entry.getStatus()) {
            case ACTIVE -> ChatFormatting.WHITE;
            case PENDING -> ChatFormatting.YELLOW;
            case ABANDONED -> ChatFormatting.RED;
            case REVOKED, EXPIRED -> ChatFormatting.DARK_GRAY;
        };
        if (!clickable) {
            return Messages.plainName(entry, colour);
        }
        return Messages.clickableName(entry, colour, Messages.hoverCard(manager, entry));
    }
}
