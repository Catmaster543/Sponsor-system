package com.fiskerz.sponsor_system.command;

import javax.annotation.Nullable;

import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.server.SponsorManager;
import com.mojang.brigadier.CommandDispatcher;
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
 * <p><strong>Permission levels.</strong> The four commands a player uses to back or stop backing someone are
 * ungated, so they sit at level 0 and every player can run them. Everything else is level 3. Brigadier filters the
 * command tree by these predicates before sending it to a client, so a player without access does not see the gated
 * commands in tab-completion at all.
 */
public final class SponsorCommands {
    /** Level 0: anyone. Applied by simply not calling {@code requires} at all. */
    public static final int PLAYER_LEVEL = 0;
    /** Level 3: operators. Everything that inspects or edits other people's relationships. */
    public static final int ADMIN_LEVEL = 3;

    private SponsorCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /** Split out from the event so tests can build the same command tree without a running server. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        InviteCommand.register(dispatcher);
        SponsorCommand.register(dispatcher);
        WithdrawCommand.register(dispatcher);
        InviteTreeCommand.register(dispatcher);
        AdminCommands.register(dispatcher);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Suggestions
    // ---------------------------------------------------------------------------------------------------------------

    /** Everyone the mod has heard of: the support graph plus {@code whitelist.json}. */
    public static final SuggestionProvider<CommandSourceStack> KNOWN_NAMES = (context, builder) -> {
        SponsorManager manager = SponsorManager.get();
        return manager == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(manager.knownNames(), builder);
    };

    /** Only the people the caller currently backs — the only ones they may withdraw from. */
    public static final SuggestionProvider<CommandSourceStack> OWN_SUPPORTED = (context, builder) -> {
        SponsorManager manager = SponsorManager.get();
        ServerPlayer player = context.getSource().getPlayer();
        if (manager == null) {
            return builder.buildFuture();
        }
        if (player == null) {
            return SharedSuggestionProvider.suggest(manager.knownNames(), builder);
        }
        return SharedSuggestionProvider.suggest(manager.supportedNames(player.getUUID()), builder);
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
     * Finds a player in the graph by name, or {@code null} after sending "not in the graph" to the source.
     * Names are display data only, so this is a lookup of convenience — the graph itself is keyed on UUID.
     */
    @Nullable
    public static SponsorEntry lookup(CommandSourceStack source, SponsorManager manager, String name) {
        SponsorEntry entry = manager.findByName(name).orElse(null);
        if (entry == null) {
            source.sendFailure(Messages.error("sponsorsystem.error.not_in_graph", Messages.name(name)));
        }
        return entry;
    }

    /** Broadcasts to everyone online, when {@code announceInvites} is on. Visible support is the point. */
    public static void announce(SponsorManager manager, Component message) {
        if (com.fiskerz.sponsor_system.Config.ANNOUNCE_INVITES.get()) {
            manager.server().getPlayerList().broadcastSystemMessage(message, false);
        }
    }
}
