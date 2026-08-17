package com.fiskerz.sponsor_system.command;

import java.util.UUID;

import javax.annotation.Nullable;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers every command and holds the helpers they share.
 *
 * <p>Permission levels used here: {@code /invite} and the read-only commands are ungated (level 0) so that any player
 * can use them; level 2 ("operator") relaxes the privacy checks on other players' trees; level 3 gates the
 * {@code /sponsorship} admin subcommands.
 */
public final class SponsorCommands {
    /** Operators bypass the whitelist in vanilla, so they are also the people allowed to look past privacy settings. */
    public static final int OPERATOR_LEVEL = 2;
    public static final int ADMIN_LEVEL = 3;

    private SponsorCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        InviteCommand.register(event.getDispatcher());
        UninviteCommand.register(event.getDispatcher());
        QueryCommands.register(event.getDispatcher());
        AdminCommands.register(event.getDispatcher());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Suggestions
    // ---------------------------------------------------------------------------------------------------------------

    /** Everyone the mod has heard of: the sponsorship tree plus {@code whitelist.json}. */
    public static final SuggestionProvider<CommandSourceStack> KNOWN_NAMES = (context, builder) -> {
        SponsorManager manager = SponsorManager.get();
        return manager == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(manager.knownNames(), builder);
    };

    /** Only the caller's own live, direct invitees — the only people {@code /uninvite} will accept. */
    public static final SuggestionProvider<CommandSourceStack> OWN_INVITEES = (context, builder) -> {
        SponsorManager manager = SponsorManager.get();
        ServerPlayer player = context.getSource().getPlayer();
        if (manager == null) {
            return builder.buildFuture();
        }
        // An admin revoking through /sponsorship should see everyone; a player should only see their own.
        if (player == null || context.getSource().hasPermission(ADMIN_LEVEL)) {
            return SharedSuggestionProvider.suggest(manager.knownNames(), builder);
        }
        return SharedSuggestionProvider.suggest(manager.directInviteeNames(player.getUUID()), builder);
    };

    // ---------------------------------------------------------------------------------------------------------------
    // Shared guards
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The running server's manager, or {@code null} after sending an error to the source. Commands are only reachable
     * while a server is running, so this should never fail in practice — but it must not throw if it does.
     */
    @Nullable
    public static SponsorManager manager(CommandSourceStack source) {
        SponsorManager manager = SponsorManager.get();
        if (manager == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.unavailable"));
        }
        return manager;
    }

    /** The calling player, or {@code null} after telling the source that this command needs a player. */
    @Nullable
    public static ServerPlayer player(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.players_only"));
        }
        return player;
    }

    /**
     * Finds a player in the tree by name, or {@code null} after sending "not in the tree" to the source.
     * Names are display data only, so this is a lookup of convenience — the tree itself is keyed on UUID.
     */
    @Nullable
    public static SponsorEntry lookup(CommandSourceStack source, SponsorManager manager, String name) {
        SponsorEntry entry = manager.findByName(name).orElse(null);
        if (entry == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.not_in_tree", Messages.name(name)));
        }
        return entry;
    }

    /**
     * Whether the source may inspect {@code subject}'s invites and subtree: always their own, always for operators,
     * and for everyone else only when {@code publicInviteTree} is on.
     */
    public static boolean mayInspect(CommandSourceStack source, UUID subject) {
        if (source.hasPermission(OPERATOR_LEVEL) || Config.PUBLIC_INVITE_TREE.get()) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && player.getUUID().equals(subject);
    }

    /** Sends a message to a player if they are still online. Used for feedback that arrives after an async lookup. */
    public static void replyIfOnline(SponsorManager manager, UUID playerId, Component message) {
        ServerPlayer player = manager.server().getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    /** Broadcasts to everyone online, when {@code announceInvites} is on. Social pressure is the point of the system. */
    public static void announce(SponsorManager manager, Component message) {
        if (Config.ANNOUNCE_INVITES.get()) {
            manager.server().getPlayerList().broadcastSystemMessage(message, false);
        }
    }
}
