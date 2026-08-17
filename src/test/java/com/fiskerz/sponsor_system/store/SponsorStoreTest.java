package com.fiskerz.sponsor_system.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;

/**
 * Tests for {@code sponsors.json} round-tripping, including the hand-edited-into-a-bad-state cases: the file is meant
 * to be readable and editable by admins, so a mistake in one entry must cost that entry and not the whole server.
 */
class SponsorStoreTest {
    @TempDir
    Path directory;

    private SponsorStore store() {
        return new SponsorStore(this.directory.resolve("sponsors.json"));
    }

    @Test
    @DisplayName("a missing file loads as an empty tree rather than failing")
    void missingFileIsEmpty() throws IOException {
        List<String> warnings = new ArrayList<>();

        assertTrue(this.store().load(warnings).isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("every field survives a save and load")
    void roundTripsEntries() throws IOException {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SponsorStore store = this.store();
        store.save(List.of(
                new SponsorEntry(rootId, "Root", null, 1_000L, 2_000L, SponsorStatus.ACTIVE),
                new SponsorEntry(childId, "Child", rootId, 3_000L, 0L, SponsorStatus.PENDING)));

        List<String> warnings = new ArrayList<>();
        List<SponsorEntry> loaded = store.load(warnings);

        assertTrue(warnings.isEmpty());
        assertEquals(2, loaded.size());

        SponsorEntry root = loaded.get(0);
        assertEquals(rootId, root.getUuid());
        assertEquals("Root", root.getLastKnownName());
        assertNull(root.getSponsor());
        assertEquals(1_000L, root.getInvitedAt());
        assertEquals(2_000L, root.getAcceptedAt());
        assertEquals(SponsorStatus.ACTIVE, root.getStatus());

        SponsorEntry child = loaded.get(1);
        assertEquals(childId, child.getUuid());
        assertEquals(rootId, child.getSponsor());
        assertEquals(SponsorStatus.PENDING, child.getStatus());
    }

    @Test
    @DisplayName("the file is written as readable JSON an admin can edit")
    void writesReadableJson() throws IOException {
        UUID uuid = UUID.randomUUID();
        this.store().save(List.of(new SponsorEntry(uuid, "Root", null, 1_000L, 0L, SponsorStatus.PENDING)));

        String written = Files.readString(this.directory.resolve("sponsors.json"), StandardCharsets.UTF_8);

        assertTrue(written.contains(uuid.toString()));
        assertTrue(written.contains("\"PENDING\""));
        assertTrue(written.contains("\n"), "the file should be pretty-printed, not one long line");
    }

    @Test
    @DisplayName("saving leaves no temp file behind")
    void cleansUpTempFile() throws IOException {
        this.store().save(List.of(new SponsorEntry(UUID.randomUUID(), "Root", null, 1L, 0L, SponsorStatus.ACTIVE)));

        assertFalse(Files.exists(this.directory.resolve("sponsors.json.tmp")));
    }

    @Test
    @DisplayName("a single broken entry is skipped, and the rest still load")
    void skipsBrokenEntries() throws IOException {
        UUID good = UUID.randomUUID();
        Files.writeString(this.directory.resolve("sponsors.json"), """
                {
                  "version": 1,
                  "entries": [
                    { "uuid": "not-a-uuid", "name": "Broken", "status": "ACTIVE" },
                    { "name": "NoUuid", "status": "ACTIVE" },
                    "not even an object",
                    { "uuid": "%s", "name": "Good", "status": "ACTIVE" }
                  ]
                }
                """.formatted(good), StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        List<SponsorEntry> loaded = this.store().load(warnings);

        assertEquals(1, loaded.size());
        assertEquals(good, loaded.get(0).getUuid());
        assertEquals(3, warnings.size(), "each skipped entry should be reported");
    }

    @Test
    @DisplayName("missing timestamps and an unknown status fall back sensibly")
    void toleratesMissingFields() throws IOException {
        UUID uuid = UUID.randomUUID();
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "entries": [ { "uuid": "%s" } ] }
                """.formatted(uuid), StandardCharsets.UTF_8);

        List<SponsorEntry> loaded = this.store().load(new ArrayList<>());

        assertEquals(1, loaded.size());
        SponsorEntry entry = loaded.get(0);
        assertEquals(0L, entry.getInvitedAt());
        // No status and no acceptedAt: an invite that was never taken up.
        assertEquals(SponsorStatus.PENDING, entry.getStatus());
    }

    @Test
    @DisplayName("an entry with a join time but no status is treated as active")
    void infersActiveFromAcceptedAt() throws IOException {
        UUID uuid = UUID.randomUUID();
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "entries": [ { "uuid": "%s", "acceptedAt": 500 } ] }
                """.formatted(uuid), StandardCharsets.UTF_8);

        assertEquals(SponsorStatus.ACTIVE, this.store().load(new ArrayList<>()).get(0).getStatus());
    }

    @Test
    @DisplayName("a file that is not JSON at all is reported rather than silently ignored")
    void rejectsGarbageFile() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), "this is not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> this.store().load(new ArrayList<>()));
    }

    @Test
    @DisplayName("a file with no entries array loads empty with a warning")
    void warnsOnMissingEntriesArray() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), "{ \"version\": 1 }", StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();

        assertTrue(this.store().load(warnings).isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("a newer file format is read as best it can be, with a warning")
    void warnsOnNewerVersion() throws IOException {
        UUID uuid = UUID.randomUUID();
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "version": 99, "entries": [ { "uuid": "%s", "status": "ACTIVE" } ] }
                """.formatted(uuid), StandardCharsets.UTF_8);

        List<String> warnings = new ArrayList<>();
        List<SponsorEntry> loaded = this.store().load(warnings);

        assertEquals(1, loaded.size());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("saving over an existing file replaces it completely")
    void overwritesCleanly() throws IOException {
        SponsorStore store = this.store();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.save(List.of(new SponsorEntry(first, "First", null, 1L, 0L, SponsorStatus.ACTIVE)));

        store.save(List.of(new SponsorEntry(second, "Second", null, 2L, 0L, SponsorStatus.ACTIVE)));

        List<SponsorEntry> loaded = store.load(new ArrayList<>());
        assertEquals(1, loaded.size());
        assertEquals(second, loaded.get(0).getUuid());
    }
}
