package com.fiskerz.sponsor_system.command;

import java.util.List;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.GraceCountdown;
import com.fiskerz.sponsor_system.graph.PersonalView;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportGraph;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /mysponsors} — a player's own standing, and nobody else's.
 *
 * <p>Level 0, and it takes <strong>no arguments on purpose</strong>. It shows who backs the caller and who the caller
 * backs, and stops there.
 *
 * <p><strong>Strictly first degree.</strong> No click events, no hover events, no player argument, no navigation of
 * any kind. It must not reveal who supports the people who support you, nor who else supports the people you support,
 * because a player who could walk their neighbours could reconstruct the whole graph one hop at a time. A fully
 * visible support graph is a map of who to lean on; keeping this first-degree lets a player understand their own
 * obligations without handing them leverage over strangers.
 *
 * <p>The statuses of directly connected players <em>are</em> shown. That is first-degree information about people the
 * caller is already answerable for, or who are already answerable for them.
 *
 * <p>An operator who needs somebody else's view uses {@code /invitetree}.
 */
public final class MySponsorsCommand {
    private MySponsorsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // No .requires and no arguments: any player, about themselves, full stop.
        dispatcher.register(Commands.literal("mysponsors")
                .executes(context -> show(context.getSource())));
    }

    private static int show(CommandSourceStack source) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        ServerPlayer caller = source.getPlayer();
        if (caller == null) {
            // Explain rather than fail obscurely: there is no sensible answer for a console or command block, since
            // the whole command is defined relative to who is asking.
            source.sendFailure(Messages.error("sponsorsystem.mysponsors.players_only"));
            return 0;
        }

        SupportGraph graph = manager.graph();
        UUID callerId = caller.getUUID();
        // One gathering step, in PersonalView, which is the single place the first-degree rule is enforced.
        PersonalView view = PersonalView.of(graph, callerId).orElse(null);
        if (view == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.self_not_in_graph"));
            return 0;
        }
        SponsorEntry self = view.self();

        MutableComponent out = Component.empty();
        out.append(Messages.header("sponsorsystem.mysponsors.header"));

        // --- your own standing, first, because that is what they opened this for ---------------------------------
        out.append(Component.literal("\n"));
        out.append(Messages.info("sponsorsystem.mysponsors.status", Messages.status(self.getStatus())));
        if (self.getStatus() == SponsorStatus.ABANDONED && self.hasGraceClock()) {
            out.append(Component.literal("\n"));
            out.append(Component.translatable("sponsorsystem.mysponsors.grace",
                            GraceCountdown.format(self.getGraceSecondsRemaining()))
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        out.append(Component.literal("\n"));
        out.append(Messages.ticketsRemaining(manager.budgetFor(callerId, EdgeKind.SPONSORSHIP)));

        // --- who is answerable for you ---------------------------------------------------------------------------
        List<SupportEdge> supporters = view.supporters();
        out.append(Component.literal("\n"));
        if (supporters.isEmpty()) {
            out.append(self.isRoot()
                    ? Messages.info("sponsorsystem.mysponsors.you_are_root")
                    : Messages.error("sponsorsystem.mysponsors.nobody_backs_you"));
        } else {
            out.append(Messages.header("sponsorsystem.mysponsors.backed_by_header", supporters.size()));
            for (SupportEdge edge : supporters) {
                out.append(Component.literal("\n"));
                out.append(supporterLine(graph, edge));
            }
            out.append(Component.literal("\n"));
            out.append(Messages.info("sponsorsystem.mysponsors.equal_note"));
        }

        // --- who you are answerable for --------------------------------------------------------------------------
        List<SupportEdge> supporting = view.supported();
        out.append(Component.literal("\n"));
        if (supporting.isEmpty()) {
            out.append(Messages.info("sponsorsystem.mysponsors.backing_nobody"));
        } else {
            out.append(Messages.header("sponsorsystem.mysponsors.backing_header", supporting.size()));
            for (SupportEdge edge : supporting) {
                out.append(Component.literal("\n"));
                out.append(supportedLine(graph, edge));
            }
        }

        source.sendSuccess(() -> out, false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * One person who backs the caller.
     *
     * <p>The inviter is labelled but not ranked: no separate section, no ordering change, no highlight. Everyone on
     * this list is equally answerable for the caller, and the display must not suggest otherwise.
     */
    private static Component supporterLine(SupportGraph graph, SupportEdge edge) {
        String name = graph.get(edge.from()).map(SponsorEntry::displayName).orElse(edge.from().toString());
        MutableComponent line = Component.literal("  - ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        if (edge.kind() == EdgeKind.PRIMARY) {
            line.append(Component.literal(" "));
            line.append(Messages.info("sponsorsystem.mysponsors.inviter_note"));
        }
        return line;
    }

    /**
     * One person the caller backs, with their status, so trouble is visible at a glance.
     *
     * <p>Their status is shown; who <em>else</em> backs them is not. That would be second degree.
     */
    private static Component supportedLine(SupportGraph graph, SupportEdge edge) {
        SponsorEntry target = graph.get(edge.to()).orElse(null);
        String name = target == null ? edge.to().toString() : target.displayName();
        MutableComponent line = Component.literal("  - ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        if (target != null) {
            line.append(Component.literal(" "));
            line.append(Messages.status(target.getStatus()));
            if (target.getStatus() == SponsorStatus.ABANDONED) {
                line.append(Component.literal(" "));
                line.append(Messages.error("sponsorsystem.mysponsors.at_risk"));
            }
        }
        return line;
    }
}
