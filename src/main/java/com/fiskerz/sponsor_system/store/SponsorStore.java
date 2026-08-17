package com.fiskerz.sponsor_system.store;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Reads and writes {@code sponsors.json}, which lives next to {@code whitelist.json} in the server root.
 *
 * <p>Like Minecraft's own player lists this is a plain, pretty-printed JSON file meant to be readable and editable by
 * hand, so parsing is done field by field: a typo in one entry costs that entry, not the whole file. Writes go to a
 * temp file which is then moved into place, so a crash mid-write cannot leave a half-written tree behind.
 *
 * <p>No Minecraft imports here either — GSON ships with the game, but nothing in this class needs a running server.
 */
public final class SponsorStore {
    /** Bumped only if the on-disk shape changes incompatibly; readers tolerate anything they recognise. */
    public static final int CURRENT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    public SponsorStore(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return this.file;
    }

    /**
     * Loads every readable entry from disk. A missing file is not an error — it just means a fresh install.
     *
     * @param warnings collects one message per skipped or repaired entry, for logging
     * @throws IOException if the file exists but cannot be read or parsed at all
     */
    public List<SponsorEntry> load(List<String> warnings) throws IOException {
        List<SponsorEntry> entries = new ArrayList<>();
        if (!Files.exists(this.file)) {
            return entries;
        }

        JsonObject root;
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException(this.file.getFileName() + " must contain a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException("Could not parse " + this.file.getFileName(), exception);
        }

        int version = root.has("version") ? root.get("version").getAsInt() : CURRENT_VERSION;
        if (version > CURRENT_VERSION) {
            warnings.add("sponsors.json was written by a newer version of the mod (format " + version
                    + "); reading it as format " + CURRENT_VERSION + " and unknown fields will be dropped on the next save.");
        }

        JsonElement entriesElement = root.get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray()) {
            warnings.add("sponsors.json has no 'entries' array; starting from an empty tree.");
            return entries;
        }

        JsonArray array = entriesElement.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                warnings.add("Entry #" + i + " in sponsors.json is not an object; skipped.");
                continue;
            }
            try {
                entries.add(readEntry(element.getAsJsonObject()));
            } catch (RuntimeException exception) {
                warnings.add("Entry #" + i + " in sponsors.json could not be read (" + exception.getMessage() + "); skipped.");
            }
        }
        return entries;
    }

    private static SponsorEntry readEntry(JsonObject object) {
        UUID uuid = readUuid(object, "uuid");
        if (uuid == null) {
            throw new IllegalArgumentException("missing or malformed 'uuid'");
        }
        String name = object.has("name") && object.get("name").isJsonPrimitive() ? object.get("name").getAsString() : null;
        UUID sponsor = readUuid(object, "sponsor");
        long invitedAt = readLong(object, "invitedAt");
        long acceptedAt = readLong(object, "acceptedAt");
        SponsorStatus status = SponsorStatus.byName(
                object.has("status") && object.get("status").isJsonPrimitive() ? object.get("status").getAsString() : null,
                acceptedAt > 0L ? SponsorStatus.ACTIVE : SponsorStatus.PENDING);
        return new SponsorEntry(uuid, name, sponsor, invitedAt, acceptedAt, status);
    }

    private static UUID readUuid(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        String raw = object.get(key).getAsString();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("'" + key + "' is not a valid UUID: " + raw);
        }
    }

    private static long readLong(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * Writes every entry out, atomically: a sibling {@code .tmp} file is written and flushed, then moved over the real
     * file. Callers save on every mutation — this is a once-per-invite operation, not a hot path.
     */
    public void save(Collection<SponsorEntry> entries) throws IOException {
        JsonArray array = new JsonArray();
        for (SponsorEntry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", entry.getUuid().toString());
            if (entry.getLastKnownName() != null) {
                object.addProperty("name", entry.getLastKnownName());
            }
            object.addProperty("sponsor", entry.getSponsor() == null ? null : entry.getSponsor().toString());
            object.addProperty("invitedAt", entry.getInvitedAt());
            object.addProperty("acceptedAt", entry.getAcceptedAt());
            object.addProperty("status", entry.getStatus().name());
            array.add(object);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.add("entries", array);

        Path parent = this.file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // Some filesystems (notably a few network mounts) cannot do this atomically; a plain replace still beats
            // writing the real file in place.
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
