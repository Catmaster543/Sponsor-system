package com.fiskerz.sponsor_system;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. Nothing here is required for the mod to work — the whole system runs on the server, and a
 * vanilla client can connect to a server running this mod. This exists solely so that a player who does happen to have
 * the mod installed gets a config screen in the Mods menu.
 *
 * <p>This class will not load on a dedicated server, so client-only types are safe to touch here.
 */
@Mod(value = Sponsorsystem.MODID, dist = Dist.CLIENT)
public class SponsorsystemClient {
    public SponsorsystemClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
