package com.fiskerz.sponsor_system.command;

import java.util.Optional;
import java.util.UUID;

import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.TicketBudget;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /sponsor <player>} — add your support to someone who is already here.
 *
 * <p>Level 0. This writes a SPONSORSHIP edge, which grants no access of its own: the target is already whitelisted.
 * What it does is make you answerable for them, exactly as answerable as whoever invited them. There is no junior
 * version of that.
 *
 * <p>Sponsoring your own inviter, or anyone else above you, is deliberately legal. It is a real statement of
 * confidence in someone, and the graph copes with the cycle it creates.
 */
public final class SponsorCommand {
    private SponsorCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sponsor")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SponsorCommands.KNOWN_NAMES)
                        .executes(context -> sponsor(context.getSource(),
                                StringArgumentType.getString(context, "player")))));
    }

    private static int sponsor(CommandSourceStack source, String targetName) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        ServerPlayer caller = SponsorCommands.player(source);
        if (caller == null) {
            return 0;
        }

        UUID callerId = caller.getUUID();
        if (!manager.graph().contains(callerId)) {
            source.sendFailure(Messages.error("sponsorsystem.invite.not_in_graph"));
            return 0;
        }

        SponsorEntry target = manager.findByName(targetName).orElse(null);
        if (target == null) {
            // Being specific here matters: the fix is a different command, not a retry.
            source.sendFailure(Messages.error("sponsorsystem.sponsor.not_here", Messages.name(targetName)));
            return 0;
        }

        boolean wasAbandoned = target.getStatus() == SponsorStatus.ABANDONED;
        TicketBudget budget = manager.budgetFor(callerId, EdgeKind.SPONSORSHIP);
        if (!budget.canSpend()) {
            source.sendFailure(Messages.error("sponsorsystem.tickets.none_left", budget.limit()));
            return 0;
        }

        String callerName = manager.graph().get(callerId).map(SponsorEntry::displayName)
                .orElse(caller.getGameProfile().getName());
        try {
            Optional<SupportEdge> edge = manager.commitSponsor(callerId, target.getUuid(), callerName, budget.spendable());
            if (edge.isEmpty()) {
                source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
                return 0;
            }
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.refusal(manager, exception, target.displayName()));
            return 0;
        }

        source.sendSuccess(() -> Messages.success("sponsorsystem.sponsor.success", Messages.name(target)), false);
        source.sendSuccess(() -> Messages.ticketsRemaining(manager.budgetFor(callerId, EdgeKind.SPONSORSHIP)), false);

        // The rescue message goes to the person who was rescued; commit() already told them support was restored,
        // so this is the supporter's side of the same event.
        if (wasAbandoned) {
            source.sendSuccess(() -> Messages.success("sponsorsystem.sponsor.rescued", Messages.name(target)), false);
        } else {
            manager.tell(target.getUuid(), Messages.success("sponsorsystem.sponsor.notify_target",
                    Messages.name(callerName)));
        }

        SponsorCommands.announce(manager, Messages.info(
                wasAbandoned ? "sponsorsystem.sponsor.announce_rescue" : "sponsorsystem.sponsor.announce",
                Messages.name(callerName), Messages.name(target)));
        Sponsorsystem.LOGGER.info("{} now supports {}{}", callerName, target.displayName(),
                wasAbandoned ? " (rescuing them from abandonment)" : "");
        return Command.SINGLE_SUCCESS;
    }
}
