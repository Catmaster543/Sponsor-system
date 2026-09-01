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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
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
 * temp file which is then moved into place, so a crash mid-write cannot leave a half-written graph behind.
 *
 * <p><strong>Schema 2</strong> splits what schema 1 kept in one place. A v1 entry carried a single {@code sponsor}
 * field, which could only express one supporter; v2 keeps players in {@code entries} and relationships in a separate
 * {@code edges} array, so a player can have any number of supporters. {@link #load} migrates a v1 file in memory and
 * {@link #save} backs the original up before the first v2 write ever replaces it.
 *
 * <p>No Minecraft imports here either — GSON ships with the game, but nothing in this class needs a running server.
 */
public final class SponsorStore {
    /** Bumped when the on-disk shape changes incompatibly. Readers migrate anything older. */
    public static final int CURRENT_VERSION = 2;
    /** The suffix used for the one-time backup taken before a v1 file is replaced by v2. */
    public static final String V1_BACKUP_SUFFIX = ".v1.bak";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    /** Set while a v1 file is in memory, so the first save can back the original up before overwriting it. */
    private boolean migratedFromV1;

    public SponsorStore(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return this.file;
    }

    /** Where the one-time v1 backup is written. */
    public Path getV1BackupFile() {
        return this.file.resolveSibling(this.file.getFileName() + V1_BACKUP_SUFFIX);
    }

    /** Whether the file most recently loaded was schema 1 and has not yet been replaced by a v2 write. */
    public boolean isMigratedFromV1() {
        return this.migratedFromV1;
    }

    /** Everything read off disk: the players, and the relationships between them. */
    public record Loaded(List<SponsorEntry> entries, List<SupportEdge> edges, int schemaVersion) {}

    /**
     * Loads the graph. A missing file is not an error — it just means a fresh install.
     *
     * @param warnings collects one message per skipped or migrated entry, for logging
     * @throws IOException if the file exists but cannot be read or parsed at all
     */
    public Loaded load(List<String> warnings) throws IOException {
        this.migratedFromV1 = false;
        if (!Files.exists(this.file)) {
            return new Loaded(new ArrayList<>(), new ArrayList<>(), CURRENT_VERSION);
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

        int version = readSchemaVersion(root);
        if (version > CURRENT_VERSION) {
            warnings.add("sponsors.json was written by a newer version of the mod (schema " + version
                    + "); reading it as schema " + CURRENT_VERSION + ". Unknown fields will be dropped on the next save.");
        }

        JsonArray entriesArray = readArray(root, "entries", warnings);
        if (entriesArray == null) {
            return new Loaded(new ArrayList<>(), new ArrayList<>(), version);
        }

        if (version <= 1) {
            this.migratedFromV1 = true;
            return migrateFromV1(entriesArray, warnings);
        }
        return readV2(root, entriesArray, warnings);
    }

    private static int readSchemaVersion(JsonObject root) {
        // v2 renamed the field; a file carrying neither is treated as the oldest shape we know.
        if (root.has("schemaVersion") && root.get("schemaVersion").isJsonPrimitive()) {
            return root.get("schemaVersion").getAsInt();
        }
        if (root.has("version") && root.get("version").isJsonPrimitive()) {
            return root.get("version").getAsInt();
        }
        return 1;
    }

    private static JsonArray readArray(JsonObject root, String key, List<String> warnings) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            warnings.add("sponsors.json has no '" + key + "' array; starting from an empty graph.");
            return null;
        }
        return element.getAsJsonArray();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schema 1 migration
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Turns a schema 1 file into the edge model.
     *
     * <p>Each entry's single {@code sponsor} field becomes one PRIMARY edge, which is exactly what it meant: the one
     * player who brought them in. An entry with no sponsor was a root, and is marked as one — that flag is why
     * withdrawing someone's last supporter later abandons them rather than promoting them.
     *
     * <p>A sponsor pointing at a player not in the file is dropped and reported. The alternative — failing the load —
     * would strand an entire server population over one bad row.
     */
    private static Loaded migrateFromV1(JsonArray entriesArray, List<String> warnings) {
        // Parsed but not yet built: whether a row is a root can only be decided once every row has been read, because
        // a sponsor pointing at a player who is not in the file has to be resolved first.
        record V1Row(UUID uuid, String name, UUID sponsor, long invitedAt, long acceptedAt, SponsorStatus status) {}

        List<V1Row> rows = new ArrayList<>();
        for (int i = 0; i < entriesArray.size(); i++) {
            JsonElement element = entriesArray.get(i);
            if (!element.isJsonObject()) {
                warnings.add("Entry #" + i + " in sponsors.json is not an object; skipped.");
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            try {
                long accepted = readLong(object, "acceptedAt");
                rows.add(new V1Row(
                        requireUuid(object, "uuid"),
                        readString(object, "name"),
                        readUuid(object, "sponsor"),
                        readLong(object, "invitedAt"),
                        accepted,
                        SponsorStatus.byName(readString(object, "status"),
                                accepted > 0L ? SponsorStatus.ACTIVE : SponsorStatus.PENDING)));
            } catch (RuntimeException exception) {
                warnings.add("Entry #" + i + " in sponsors.json could not be read (" + exception.getMessage() + "); skipped.");
            }
        }

        Set<UUID> known = new HashSet<>();
        rows.forEach(row -> known.add(row.uuid()));

        List<SponsorEntry> entries = new ArrayList<>();
        List<SupportEdge> edges = new ArrayList<>();
        for (V1Row row : rows) {
            UUID sponsor = row.sponsor();
            boolean isRoot;
            if (sponsor == null) {
                // A v1 entry with no sponsor was a root, and stays one.
                isRoot = true;
            } else if (sponsor.equals(row.uuid())) {
                warnings.add("Migration: " + row.uuid() + " was recorded as their own sponsor; the link was dropped "
                        + "and they were made a root.");
                isRoot = true;
            } else if (!known.contains(sponsor)) {
                // Promoting to root rather than leaving them supporter-less is deliberate: the latter would abandon
                // them on the very first recompute, turning one bad row in the old file into a lost player.
                warnings.add("Migration: " + row.uuid() + " pointed at unknown sponsor " + sponsor
                        + "; the link was dropped and they were made a root.");
                isRoot = true;
            } else {
                isRoot = false;
                // The edge is as old as the invite it represents, so anti-flap timing survives the migration.
                edges.add(new SupportEdge(sponsor, row.uuid(), EdgeKind.PRIMARY, row.invitedAt()));
            }
            entries.add(new SponsorEntry(row.uuid(), row.name(), row.invitedAt(), row.acceptedAt(), row.status(), isRoot));
        }

        warnings.add("Migrated sponsors.json from schema 1 to schema 2: " + entries.size() + " player(s), "
                + edges.size() + " support edge(s). The original will be backed up as a "
                + V1_BACKUP_SUFFIX + " file on the next save.");
        return new Loaded(entries, edges, 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schema 2
    // ---------------------------------------------------------------------------------------------------------------

    private static Loaded readV2(JsonObject root, JsonArray entriesArray, List<String> warnings) {
        List<SponsorEntry> entries = new ArrayList<>();
        for (int i = 0; i < entriesArray.size(); i++) {
            JsonElement element = entriesArray.get(i);
            if (!element.isJsonObject()) {
                warnings.add("Entry #" + i + " in sponsors.json is not an object; skipped.");
                continue;
            }
            try {
                JsonObject object = element.getAsJsonObject();
                UUID uuid = requireUuid(object, "uuid");
                long accepted = readLong(object, "acceptedAt");
                SponsorStatus status = SponsorStatus.byName(readString(object, "status"),
                        accepted > 0L ? SponsorStatus.ACTIVE : SponsorStatus.PENDING);
                boolean isRoot = object.has("root") && object.get("root").isJsonPrimitive()
                        && object.get("root").getAsBoolean();
                entries.add(new SponsorEntry(uuid, readString(object, "name"), readLong(object, "invitedAt"),
                        accepted, status, isRoot));
            } catch (RuntimeException exception) {
                warnings.add("Entry #" + i + " in sponsors.json could not be read (" + exception.getMessage() + "); skipped.");
            }
        }

        List<SupportEdge> edges = new ArrayList<>();
        JsonElement edgesElement = root.get("edges");
        if (edgesElement != null && edgesElement.isJsonArray()) {
            JsonArray edgesArray = edgesElement.getAsJsonArray();
            for (int i = 0; i < edgesArray.size(); i++) {
                JsonElement element = edgesArray.get(i);
                if (!element.isJsonObject()) {
                    warnings.add("Edge #" + i + " in sponsors.json is not an object; skipped.");
                    continue;
                }
                try {
                    JsonObject object = element.getAsJsonObject();
                    edges.add(new SupportEdge(requireUuid(object, "from"), requireUuid(object, "to"),
                            EdgeKind.byName(readString(object, "kind"), EdgeKind.SPONSORSHIP),
                            readLong(object, "createdAt")));
                } catch (RuntimeException exception) {
                    warnings.add("Edge #" + i + " in sponsors.json could not be read (" + exception.getMessage() + "); skipped.");
                }
            }
        } else {
            warnings.add("sponsors.json has no 'edges' array; every player will load with no supporters.");
        }
        return new Loaded(entries, edges, 2);
    }

    private static UUID requireUuid(JsonObject object, String key) {
        UUID uuid = readUuid(object, key);
        if (uuid == null) {
            throw new IllegalArgumentException("missing or malformed '" + key + "'");
        }
        return uuid;
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

    private static String readString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
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

    // ---------------------------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Writes the graph out, atomically: a sibling {@code .tmp} file is written and flushed, then moved over the real
     * file. Callers save on every mutation — this is a once-per-command operation, not a hot path.
     *
     * <p>The first save after loading a schema 1 file copies the original aside first, so a migration that turns out
     * to be wrong can be undone by hand.
     */
    public void save(Collection<SponsorEntry> entries, Collection<SupportEdge> edges) throws IOException {
        Path parent = this.file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (this.migratedFromV1 && Files.exists(this.file)) {
            Path backup = this.getV1BackupFile();
            if (!Files.exists(backup)) {
                Files.copy(this.file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            this.migratedFromV1 = false;
        }

        JsonArray entriesJson = new JsonArray();
        for (SponsorEntry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", entry.getUuid().toString());
            if (entry.getLastKnownName() != null) {
                object.addProperty("name", entry.getLastKnownName());
            }
            object.addProperty("invitedAt", entry.getInvitedAt());
            object.addProperty("acceptedAt", entry.getAcceptedAt());
            object.addProperty("status", entry.getStatus().name());
            if (entry.isRoot()) {
                object.addProperty("root", true);
            }
            entriesJson.add(object);
        }

        JsonArray edgesJson = new JsonArray();
        for (SupportEdge edge : edges) {
            JsonObject object = new JsonObject();
            object.addProperty("from", edge.from().toString());
            object.addProperty("to", edge.to().toString());
            object.addProperty("kind", edge.kind().name());
            object.addProperty("createdAt", edge.createdAt());
            edgesJson.add(object);
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_VERSION);
        root.add("entries", entriesJson);
        root.add("edges", edgesJson);

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
