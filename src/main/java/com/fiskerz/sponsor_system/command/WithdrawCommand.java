package com.fiskerz.sponsor_system.command;

import java.util.Optional;
import java.util.UUID;

import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportModel;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /uninvite <player>} and {@code /unsponsor <player>} — take back your own support.
 *
 * <p>These are two names for one operation, registered as aliases. Because every supporter carries the same
 * responsibility, taking back an invite and taking back a sponsorship are the same act: remove <em>your</em> edge to
 * that player, whichever kind it happens to be. A player who reaches for the "wrong" verb still gets what they meant.
 *
 * <p>Nothing is unwhitelisted or kicked here. The recompute afterwards decides who has lost support; as of this round
 * that is recorded and announced, not enforced.
 */
public final class WithdrawCommand {
    private WithdrawCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("uninvite"));
        dispatcher.register(build("unsponsor"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SponsorCommands.OWN_SUPPORTED)
                        .executes(context -> withdraw(context.getSource(), literal,
                                StringArgumentType.getString(context, "player"), false))
                        // The confirmation the warning offers re-runs the very same command with this flag on.
                        .then(Commands.literal("confirm")
                                .executes(context -> withdraw(context.getSource(), literal,
                                        StringArgumentType.getString(context, "player"), true))));
    }

    private static int withdraw(CommandSourceStack source, String literal, String targetName, boolean confirmed) {
        SponsorManager manager = SponsorCommands.manager(source);
        if (manager == null) {
            return 0;
        }
        ServerPlayer caller = SponsorCommands.player(source);
        if (caller == null) {
            return 0;
        }
        SponsorEntry target = SponsorCommands.lookup(source, manager, targetName);
        if (target == null) {
            return 0;
        }

        UUID callerId = caller.getUUID();
        Optional<SupportEdge> edge = manager.graph().edge(callerId, target.getUuid());
        if (edge.isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.withdraw.not_yours", Messages.name(target)));
            return 0;
        }

        boolean isOperator = manager.isOperator(caller.getGameProfile());
        if (!isOperator && !edge.get().isPrimary()) {
            long wait = Messages.minutesUntilWithdrawable(edge.get().createdAt(),
                    manager.sponsorshipMinDurationMillis(), System.currentTimeMillis());
            if (wait > 0L) {
                source.sendFailure(Messages.error("sponsorsystem.withdraw.too_young_wait", Messages.name(target), wait));
                return 0;
            }
        }

        // Ask before doing something that strands somebody. Working this out means running the recompute against a
        // graph with the edge already gone, so it is done as a dry run rather than by mutating first.
        if (!confirmed && wouldAbandon(manager, callerId, target.getUuid())) {
            source.sendSuccess(() -> Messages.error("sponsorsystem.withdraw.confirm_warning", Messages.name(target)), false);
            source.sendSuccess(() -> Component.empty()
                    .append(Messages.info("sponsorsystem.withdraw.confirm_prompt"))
                    .append(Component.literal(" "))
                    .append(Messages.confirmButton("/" + literal + " " + target.displayName() + " confirm")), false);
            return 0;
        }

        Optional<SponsorManager.WithdrawResult> result;
        try {
            result = manager.commitWithdraw(callerId, target.getUuid(), isOperator);
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.refusal(manager, exception, target.displayName()));
            return 0;
        }
        if (result.isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        String callerName = manager.graph().get(callerId).map(SponsorEntry::displayName)
                .orElse(caller.getGameProfile().getName());
        source.sendSuccess(() -> Messages.success("sponsorsystem.withdraw.success", Messages.name(target)), false);
        source.sendSuccess(() -> Messages.ticketsRemaining(manager.budgetFor(callerId, edge.get().kind())), false);

        SupportEdge promoted = result.get().promoted();
        if (promoted != null) {
            String promotedName = manager.graph().get(promoted.from()).map(SponsorEntry::displayName)
                    .orElse(promoted.from().toString());
            // Deliberately worded as bookkeeping. That supporter did not just become responsible for anyone; they
            // already were, exactly as much as everybody else backing this player.
            source.sendSuccess(() -> Messages.info("sponsorsystem.withdraw.promoted",
                    Messages.name(target), Messages.name(promotedName)), false);
        }

        SponsorCommands.announce(manager, Messages.info("sponsorsystem.withdraw.announce",
                Messages.name(callerName), Messages.name(target)));
        Sponsorsystem.LOGGER.info("{} withdrew support from {}{}", callerName, target.displayName(),
                result.get().abandonedTarget() ? " (leaving them abandoned)" : "");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Whether removing this edge would leave the target with no support.
     *
     * <p>Computed without touching the live graph: the supported set is recomputed with this one edge ignored, so the
     * answer is exactly what the real recompute would produce, and the caller can still back out.
     */
    private static boolean wouldAbandon(SponsorManager manager, UUID supporter, UUID supported) {
        SupportModel model = manager.supportModel();
        return !manager.graph().computeSupportedIgnoring(model, supporter, supported).contains(supported);
    }
}
