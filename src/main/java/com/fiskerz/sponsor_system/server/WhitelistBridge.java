package com.fiskerz.sponsor_system.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fiskerz.sponsor_system.Sponsorsystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

/**
 * Everything this mod does to the vanilla whitelist.
 *
 * <p>{@link UserWhiteList#add} and {@code remove} rewrite {@code whitelist.json} as they go, which is what makes
 * {@code /invite} take effect without a restart or a {@code /whitelist reload}.
 *
 * <p>Listing the whitelist with UUIDs is done by reading {@code whitelist.json} rather than walking the in-memory
 * entries: {@code StoredUserEntry#getUser()} is package-private in 1.21.1, and reaching it would need an Access
 * Transformer. The file is authoritative anyway — vanilla rewrites it on every add and remove — and
 * {@link UserWhiteList#getFile()} tells us exactly where it is, so nothing here hardcodes a path.
 */
public final class WhitelistBridge {
    private final MinecraftServer server;

    public WhitelistBridge(MinecraftServer server) {
        this.server = server;
    }

    private UserWhiteList whitelist() {
        return this.server.getPlayerList().getWhiteList();
    }

    /** @return {@code true} if the profile was added, {@code false} if it was already whitelisted */
    public boolean add(GameProfile profile) {
        if (this.isWhitelisted(profile)) {
            return false;
        }
        this.whitelist().add(new UserWhiteListEntry(profile));
        return true;
    }

    /** Removal keys on UUID only, so the name carried by the profile does not have to be current. */
    public boolean remove(UUID uuid, String name) {
        GameProfile profile = new GameProfile(uuid, name == null ? "" : name);
        if (!this.isWhitelisted(profile)) {
            return false;
        }
        this.whitelist().remove(profile);
        return true;
    }

    public boolean isWhitelisted(GameProfile profile) {
        return this.whitelist().isWhiteListed(profile);
    }

    public boolean isWhitelisted(UUID uuid) {
        return this.isWhitelisted(new GameProfile(uuid, ""));
    }

    public boolean isEnforced() {
        return this.server.getPlayerList().isUsingWhitelist();
    }

    public void setEnforced(boolean enforced) {
        this.server.getPlayerList().setUsingWhiteList(enforced);
    }

    /** Names only — cheap, in-memory, and enough for tab completion. */
    public String[] names() {
        return this.server.getPlayerList().getWhiteListNames();
    }

    /**
     * Finds an already-whitelisted profile by name. Lets {@code /sponsorship adopt} bring in a pre-existing whitelist
     * entry without a Mojang round trip — and works on offline-mode servers, where a lookup would be meaningless.
     */
    public Optional<GameProfile> findByName(String name) {
        return this.entries().stream().filter(profile -> profile.getName().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Every whitelisted profile, read back from {@code whitelist.json}. Returns an empty list (and logs) if the file
     * is missing or unreadable, so reconciliation degrades to "assume nothing is whitelisted" rather than failing
     * startup.
     */
    public List<GameProfile> entries() {
        List<GameProfile> profiles = new ArrayList<>();
        Path path = this.whitelist().getFile().toPath();
        if (!Files.exists(path)) {
            return profiles;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonArray()) {
                Sponsorsystem.LOGGER.warn("{} is not a JSON array; treating the whitelist as empty for reconciliation.",
                        path.getFileName());
                return profiles;
            }
            JsonArray array = parsed.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (!object.has("uuid") || !object.has("name")) {
                    continue;
                }
                try {
                    profiles.add(new GameProfile(UUID.fromString(object.get("uuid").getAsString()),
                            object.get("name").getAsString()));
                } catch (IllegalArgumentException exception) {
                    Sponsorsystem.LOGGER.warn("Skipping whitelist entry with a malformed UUID: {}", object);
                }
            }
        } catch (IOException | JsonParseException exception) {
            Sponsorsystem.LOGGER.warn("Could not read {} for reconciliation; continuing without it.",
                    path.getFileName(), exception);
        }
        return profiles;
    }
}
