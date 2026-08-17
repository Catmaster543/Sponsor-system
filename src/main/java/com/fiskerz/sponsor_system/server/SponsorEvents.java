package com.fiskerz.sponsor_system.server;

import com.fiskerz.sponsor_system.Sponsorsystem;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Ties the {@link SponsorManager} to the server lifecycle. Registered on the game bus.
 */
public final class SponsorEvents {
    private SponsorEvents() {}

    /**
     * Started, not Starting: the player list and its whitelist are fully loaded by this point, so reconciliation sees
     * the real contents of {@code whitelist.json}.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        SponsorManager.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SponsorManager manager = SponsorManager.get();
        if (manager != null) {
            manager.saveOnShutdown();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SponsorManager.stop();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        SponsorManager manager = SponsorManager.get();
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.onPlayerLoggedIn(player);
        }
    }

    /** Drives the pending-invite expiry sweep. The manager rate-limits itself to one sweep an hour. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SponsorManager manager = SponsorManager.get();
        if (manager != null) {
            try {
                manager.tick();
            } catch (RuntimeException exception) {
                Sponsorsystem.LOGGER.error("Sponsorship expiry sweep failed; it will be retried in an hour.", exception);
            }
        }
    }
}
