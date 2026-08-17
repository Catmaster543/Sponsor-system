package com.fiskerz.sponsor_system;

import org.slf4j.Logger;

import com.fiskerz.sponsor_system.command.SponsorCommands;
import com.fiskerz.sponsor_system.server.SponsorEvents;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Sponsorship: access to the server is a tree of personal responsibility.
 *
 * <p>This mod is server-side only in practice. It registers no blocks, items, or other registry content, and no
 * network payloads, which is what lets a vanilla client connect to a server running it — NeoForge only refuses a
 * vanilla connection when a mod has registered a non-optional payload. Note that {@code displayTest} in
 * {@code neoforge.mods.toml} no longer exists in NeoForge 1.21.1; it was removed along with the old networking stack,
 * and registering nothing client-relevant is the modern equivalent.
 */
@Mod(Sponsorsystem.MODID)
public class Sponsorsystem {
    public static final String MODID = "sponsorsystem";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Sponsorsystem(IEventBus modEventBus, ModContainer modContainer) {
        // SERVER config: this lives with the server/world, not with a client installation.
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        // Everything this mod does hangs off game bus events, not the mod bus.
        NeoForge.EVENT_BUS.register(SponsorEvents.class);
        NeoForge.EVENT_BUS.register(SponsorCommands.class);
    }
}
