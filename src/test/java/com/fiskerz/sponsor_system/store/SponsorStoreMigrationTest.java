package com.fiskerz.sponsor_system.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportGraph;
import com.fiskerz.sponsor_system.graph.SupportModel;

/**
 * Schema 1 to schema 2 migration.
 *
 * <p>This is the highest-consequence code in the round: a botched migration abandons a whole server population in one
 * go, so it is tested against a realistic multi-level file rather than a toy one.
 */
class SponsorStoreMigrationTest {
    @TempDir
    Path directory;

    private static final UUID ROOT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOB = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CAROL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DAVE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ECHO = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private SponsorStore store() {
        return new SponsorStore(this.directory.resolve("sponsors.json"));
    }

    /** A realistic v1 file: four levels, a second branch, a revoked leaf, and a never-joined invite. */
    private void writeV1() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                {
                  "version": 1,
                  "entries": [
                    { "uuid": "%s", "name": "Root",  "sponsor": null, "invitedAt": 1000, "acceptedAt": 1100, "status": "ACTIVE" },
                    { "uuid": "%s", "name": "Alice", "sponsor": "%s", "invitedAt": 2000, "acceptedAt": 2100, "status": "ACTIVE" },
                    { "uuid": "%s", "name": "Bob",   "sponsor": "%s", "invitedAt": 3000, "acceptedAt": 3100, "status": "ACTIVE" },
                    { "uuid": "%s", "name": "Carol", "sponsor": "%s", "invitedAt": 4000, "acceptedAt": 0,    "status": "PENDING" },
                    { "uuid": "%s", "name": "Dave",  "sponsor": "%s", "invitedAt": 5000, "acceptedAt": 5100, "status": "ACTIVE" },
                    { "uuid": "%s", "name": "Echo",  "sponsor": "%s", "invitedAt": 6000, "acceptedAt": 0,    "status": "REVOKED" }
                  ]
                }
                """.formatted(ROOT, ALICE, ROOT, BOB, ALICE, CAROL, BOB, DAVE, ROOT, ECHO, ALICE),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every player and every relationship survives a multi-level migration")
    void migratesMultiLevelFile() throws IOException {
        this.writeV1();
        List<String> warnings = new ArrayList<>();

        SponsorStore.Loaded loaded = this.store().load(warnings);

        assertEquals(1, loaded.schemaVersion());
        assertEquals(6, loaded.entries().size());
        assertEquals(5, loaded.edges().size(), "every non-null sponsor becomes exactly one edge");

        Map<UUID, SupportEdge> byTarget = new java.util.HashMap<>();
        loaded.edges().forEach(edge -> byTarget.put(edge.to(), edge));
        assertEquals(ROOT, byTarget.get(ALICE).from());
        assertEquals(ALICE, byTarget.get(BOB).from());
        assertEquals(BOB, byTarget.get(CAROL).from());
        assertEquals(ROOT, byTarget.get(DAVE).from());
        assertEquals(ALICE, byTarget.get(ECHO).from());
        assertFalse(byTarget.containsKey(ROOT), "the root has no incoming edge");
    }

    @Test
    @DisplayName("every migrated edge is primary, because that is what a v1 sponsor meant")
    void allMigratedEdgesArePrimary() throws IOException {
        this.writeV1();

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertTrue(loaded.edges().stream().allMatch(SupportEdge::isPrimary));
    }

    @Test
    @DisplayName("only the sponsor-less entry becomes a root")
    void onlySponsorlessBecomesRoot() throws IOException {
        this.writeV1();

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        List<UUID> roots = loaded.entries().stream().filter(SponsorEntry::isRoot).map(SponsorEntry::getUuid).toList();
        assertEquals(List.of(ROOT), roots);
    }

    @Test
    @DisplayName("statuses and timestamps come across untouched")
    void preservesFields() throws IOException {
        this.writeV1();

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());
        Map<UUID, SponsorEntry> byId = new java.util.HashMap<>();
        loaded.entries().forEach(entry -> byId.put(entry.getUuid(), entry));

        assertEquals(SponsorStatus.PENDING, byId.get(CAROL).getStatus());
        assertEquals(SponsorStatus.REVOKED, byId.get(ECHO).getStatus());
        assertEquals(4000L, byId.get(CAROL).getInvitedAt());
        assertEquals(2100L, byId.get(ALICE).getAcceptedAt());
        assertEquals("Alice", byId.get(ALICE).getLastKnownName());
    }

    @Test
    @DisplayName("edges inherit the invite time, so anti-flap timing survives the migration")
    void edgesKeepInviteTime() throws IOException {
        this.writeV1();

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        SupportEdge toBob = loaded.edges().stream().filter(edge -> edge.to().equals(BOB)).findFirst().orElseThrow();
        assertEquals(3000L, toBob.createdAt());
    }

    @Test
    @DisplayName("the migrated graph loads into a working support model")
    void migratedGraphIsCoherent() throws IOException {
        this.writeV1();
        SupportGraph graph = new SupportGraph();

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());
        List<String> warnings = graph.replaceAll(loaded.entries(), loaded.edges());

        assertTrue(warnings.isEmpty(), "a clean v1 file should migrate without repairs: " + warnings);
        // Everyone except the revoked leaf is reachable from the root, so nobody is stranded by the migration.
        SupportGraph.SupportChange change = graph.recomputeSupport(SupportModel.REACHABILITY);
        assertTrue(change.abandoned().isEmpty(), "migration must not abandon anyone: " + change.abandoned());
        assertEquals(SponsorStatus.REVOKED, graph.get(ECHO).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a sponsor who is not in the file becomes a root rather than a stranded player")
    void danglingSponsorBecomesRoot() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                {
                  "version": 1,
                  "entries": [
                    { "uuid": "%s", "name": "Orphan", "sponsor": "%s", "invitedAt": 1, "acceptedAt": 2, "status": "ACTIVE" }
                  ]
                }
                """.formatted(ALICE, UUID.randomUUID()), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();

        SponsorStore.Loaded loaded = this.store().load(warnings);

        assertEquals(1, loaded.entries().size());
        assertTrue(loaded.entries().get(0).isRoot(),
                "leaving them supporter-less would abandon them on the first recompute");
        assertEquals(0, loaded.edges().size());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("unknown sponsor")));
    }

    @Test
    @DisplayName("a self-sponsoring v1 entry becomes a root")
    void selfSponsorBecomesRoot() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "version": 1, "entries": [
                  { "uuid": "%s", "name": "Loop", "sponsor": "%s", "invitedAt": 1, "acceptedAt": 2, "status": "ACTIVE" } ] }
                """.formatted(ALICE, ALICE), StandardCharsets.UTF_8);

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertTrue(loaded.entries().get(0).isRoot());
        assertEquals(0, loaded.edges().size());
    }

    @Test
    @DisplayName("the original file is backed up before the first v2 write")
    void backsUpBeforeFirstWrite() throws IOException {
        this.writeV1();
        String original = Files.readString(this.directory.resolve("sponsors.json"), StandardCharsets.UTF_8);
        SponsorStore store = this.store();
        SponsorStore.Loaded loaded = store.load(new ArrayList<>());

        assertTrue(store.isMigratedFromV1());
        store.save(loaded.entries(), loaded.edges());

        Path backup = this.directory.resolve("sponsors.json" + SponsorStore.V1_BACKUP_SUFFIX);
        assertTrue(Files.exists(backup), "the v1 file must be recoverable by hand");
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        assertFalse(store.isMigratedFromV1(), "the backup is taken once, not on every save");
    }

    @Test
    @DisplayName("a second save does not overwrite the backup with migrated data")
    void backupIsTakenOnlyOnce() throws IOException {
        this.writeV1();
        String original = Files.readString(this.directory.resolve("sponsors.json"), StandardCharsets.UTF_8);
        SponsorStore store = this.store();
        SponsorStore.Loaded loaded = store.load(new ArrayList<>());

        store.save(loaded.entries(), loaded.edges());
        store.save(loaded.entries(), loaded.edges());

        Path backup = this.directory.resolve("sponsors.json" + SponsorStore.V1_BACKUP_SUFFIX);
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the migrated file round-trips as schema 2")
    void roundTripsAsV2() throws IOException {
        this.writeV1();
        SponsorStore store = this.store();
        SponsorStore.Loaded migrated = store.load(new ArrayList<>());
        store.save(migrated.entries(), migrated.edges());

        List<String> warnings = new ArrayList<>();
        SponsorStore.Loaded reloaded = new SponsorStore(this.directory.resolve("sponsors.json")).load(warnings);

        assertEquals(2, reloaded.schemaVersion());
        assertTrue(warnings.isEmpty(), "a file we just wrote should reload cleanly: " + warnings);
        assertEquals(migrated.entries().size(), reloaded.entries().size());
        assertEquals(migrated.edges().size(), reloaded.edges().size());
        assertEquals(1, reloaded.entries().stream().filter(SponsorEntry::isRoot).count());
    }

    @Test
    @DisplayName("a v1 file with no version field at all is still recognised as v1")
    void treatsMissingVersionAsV1() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "entries": [ { "uuid": "%s", "name": "Root", "acceptedAt": 5, "status": "ACTIVE" } ] }
                """.formatted(ROOT), StandardCharsets.UTF_8);

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertEquals(1, loaded.schemaVersion());
        assertTrue(loaded.entries().get(0).isRoot());
    }

    @Test
    @DisplayName("a schema 2 file is read directly, with no migration and no backup")
    void readsV2Directly() throws IOException {
        SponsorStore store = this.store();
        store.save(
                List.of(new SponsorEntry(ROOT, "Root", 1L, 2L, SponsorStatus.ACTIVE, true),
                        new SponsorEntry(ALICE, "Alice", 3L, 4L, SponsorStatus.ABANDONED, false)),
                List.of(new SupportEdge(ROOT, ALICE, EdgeKind.SPONSORSHIP, 9L)));

        List<String> warnings = new ArrayList<>();
        SponsorStore.Loaded loaded = this.store().load(warnings);

        assertEquals(2, loaded.schemaVersion());
        assertTrue(warnings.isEmpty());
        assertEquals(SponsorStatus.ABANDONED, loaded.entries().get(1).getStatus(), "the new status persists");
        assertEquals(EdgeKind.SPONSORSHIP, loaded.edges().get(0).kind());
        assertEquals(9L, loaded.edges().get(0).createdAt());
        assertFalse(Files.exists(this.directory.resolve("sponsors.json" + SponsorStore.V1_BACKUP_SUFFIX)));
    }

    @Test
    @DisplayName("a broken edge row is skipped without costing the rest of the file")
    void skipsBrokenEdge() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                {
                  "schemaVersion": 2,
                  "entries": [
                    { "uuid": "%s", "name": "Root", "status": "ACTIVE", "root": true },
                    { "uuid": "%s", "name": "Alice", "status": "ACTIVE" }
                  ],
                  "edges": [
                    { "from": "not-a-uuid", "to": "%s", "kind": "PRIMARY", "createdAt": 1 },
                    { "from": "%s", "to": "%s", "kind": "PRIMARY", "createdAt": 2 }
                  ]
                }
                """.formatted(ROOT, ALICE, ALICE, ROOT, ALICE), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();

        SponsorStore.Loaded loaded = this.store().load(warnings);

        assertEquals(2, loaded.entries().size());
        assertEquals(1, loaded.edges().size());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("a schema 2 file with no edges array loads the players and says so")
    void warnsOnMissingEdges() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "schemaVersion": 2, "entries": [ { "uuid": "%s", "name": "Root", "root": true } ] }
                """.formatted(ROOT), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();

        SponsorStore.Loaded loaded = this.store().load(warnings);

        assertEquals(1, loaded.entries().size());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("edges")));
    }

    @Test
    @DisplayName("a missing file is an empty schema 2 graph, not an error")
    void missingFileIsEmpty() throws IOException {
        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertTrue(loaded.entries().isEmpty());
        assertTrue(loaded.edges().isEmpty());
    }

    @Test
    @DisplayName("a file that is not JSON at all is reported rather than silently ignored")
    void rejectsGarbage() throws IOException {
        Files.writeString(this.directory.resolve("sponsors.json"), "this is not json", StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> this.store().load(new ArrayList<>()));
    }

    @Test
    @DisplayName("a grace clock survives a full save and reload, which is what a server restart is")
    void gracePersistsAcrossRestart() throws IOException {
        SponsorStore store = this.store();
        SponsorEntry abandoned = new SponsorEntry(ALICE, "Alice", 1L, 2L, SponsorStatus.ABANDONED, false, 12 * 60);
        store.save(List.of(new SponsorEntry(ROOT, "Root", 1L, 2L, SponsorStatus.ACTIVE, true), abandoned),
                List.of());

        SponsorStore.Loaded reloaded = new SponsorStore(this.directory.resolve("sponsors.json")).load(new ArrayList<>());

        SponsorEntry back = reloaded.entries().stream().filter(entry -> entry.getUuid().equals(ALICE))
                .findFirst().orElseThrow();
        assertEquals(SponsorStatus.ABANDONED, back.getStatus());
        assertTrue(back.hasGraceClock());
        assertEquals(12 * 60, back.getGraceSecondsRemaining(),
                "a restart must neither reset the clock nor skip it forward");
    }

    @Test
    @DisplayName("a player with no clock round-trips without gaining one")
    void noClockRoundTrips() throws IOException {
        SponsorStore store = this.store();
        store.save(List.of(new SponsorEntry(ROOT, "Root", 1L, 2L, SponsorStatus.ACTIVE, true)), List.of());

        SponsorStore.Loaded reloaded = this.store().load(new ArrayList<>());

        assertFalse(reloaded.entries().get(0).hasGraceClock());
        assertEquals(SponsorEntry.NO_GRACE, reloaded.entries().get(0).getGraceSecondsRemaining());
    }

    @Test
    @DisplayName("a file written before the clock existed loads with no clock, not a zero one")
    void olderFileHasNoClock() throws IOException {
        // A zero would mean "expire immediately"; absent must mean "no clock yet".
        Files.writeString(this.directory.resolve("sponsors.json"), """
                { "schemaVersion": 2, "entries": [
                  { "uuid": "%s", "name": "Alice", "status": "ABANDONED" } ], "edges": [] }
                """.formatted(ALICE), StandardCharsets.UTF_8);

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertFalse(loaded.entries().get(0).hasGraceClock());
    }

    @Test
    @DisplayName("the expired status round-trips")
    void expiredRoundTrips() throws IOException {
        this.store().save(List.of(new SponsorEntry(ALICE, "Alice", 1L, 2L, SponsorStatus.EXPIRED, false)), List.of());

        SponsorStore.Loaded loaded = this.store().load(new ArrayList<>());

        assertEquals(SponsorStatus.EXPIRED, loaded.entries().get(0).getStatus());
        assertFalse(loaded.entries().get(0).getStatus().isLive());
    }

    @Test
    @DisplayName("saving leaves no temp file behind")
    void cleansUpTempFile() throws IOException {
        this.store().save(List.of(new SponsorEntry(ROOT, "Root", 1L, 0L, SponsorStatus.ACTIVE, true)), List.of());

        assertFalse(Files.exists(this.directory.resolve("sponsors.json.tmp")));
    }
}
