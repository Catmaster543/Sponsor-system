package com.fiskerz.sponsor_system.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraphException;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /uninvite <name>} — withdraw a sponsorship you personally issued.
 *
 * <p>Restricted to your own direct invitees: you can only take back what you vouched for. Admins who need to revoke
 * anyone use {@code /sponsorship revoke} instead.
 *
 * <p>With {@code cascadeOnRevoke} on (the default) this takes down the invitee's entire branch, because everyone in it
 * is on the server on the strength of the invite being withdrawn.
 */
public final class UninviteCommand {
    private UninviteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("uninvite")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SponsorCommands.OWN_INVITEES)
                        .executes(context -> uninvite(context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    private static int uninvite(CommandSourceStack source, String targetName) {
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
        if (!callerId.equals(target.getSponsor())) {
            source.sendFailure(Messages.error("sponsorsystem.uninvite.not_yours", Messages.name(target)));
            return 0;
        }
        if (!target.getStatus().isLive()) {
            source.sendFailure(Messages.error("sponsorsystem.uninvite.already_revoked", Messages.name(target)));
            return 0;
        }

        String sponsorName = manager.graph().get(callerId).map(SponsorEntry::displayName)
                .orElse(caller.getGameProfile().getName());
        // The kick names who revoked whom, so nobody is disconnected without knowing why.
        Component kickReason = Component.translatable("sponsorsystem.kick.revoked",
                target.displayName(), sponsorName);

        boolean cascade = Config.CASCADE_ON_REVOKE.get();
        Optional<List<SponsorEntry>> revoked;
        try {
            revoked = manager.commitRevoke(target.getUuid(), cascade, kickReason);
        } catch (SponsorGraphException exception) {
            source.sendFailure(Messages.error("sponsorsystem.error.not_in_tree", Messages.name(target)));
            return 0;
        }
        if (revoked.isEmpty()) {
            source.sendFailure(Messages.error("sponsorsystem.error.save_failed"));
            return 0;
        }

        int alsoRemoved = Math.max(0, revoked.get().size() - 1);
        if (alsoRemoved > 0) {
            source.sendSuccess(() -> Messages.success("sponsorsystem.uninvite.success_cascade",
                    Messages.name(target), alsoRemoved), false);
        } else {
            source.sendSuccess(() -> Messages.success("sponsorsystem.uninvite.success", Messages.name(target)), false);
        }

        SponsorCommands.announce(manager, Messages.info("sponsorsystem.uninvite.announce",
                Messages.name(sponsorName), Messages.name(target)));
        Sponsorsystem.LOGGER.info("{} revoked their sponsorship of {}; {} player(s) removed from the whitelist.",
                sponsorName, target.displayName(), revoked.get().size());
        return Command.SINGLE_SUCCESS;
    }
}
