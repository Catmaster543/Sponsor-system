package com.fiskerz.sponsor_system.graph;

import java.util.Objects;
import java.util.UUID;

/**
 * One player in the support graph.
 *
 * <p>Who supports this player is <em>not</em> stored here — that lives in the edge set, because a player may have any
 * number of supporters. This holds only what is true of the player themselves.
 *
 * <p>The UUID is the only identity key. {@link #getLastKnownName()} exists so admins can read {@code sponsors.json}
 * and so chat can show something human; it is refreshed on every login and must never be used to look an entry up.
 */
public final class SponsorEntry {
    /** Sentinel for "no grace clock is running for this player". */
    public static final int NO_GRACE = -1;

    private final UUID uuid;
    private String lastKnownName;
    private long invitedAt;
    private long acceptedAt;
    private SponsorStatus status;
    /**
     * Whether this player is an anchor of the graph: the founder, or someone an operator adopted with no
     * supporter. Persisted, and deliberately NOT inferred from having no incoming edges - a player whose last
     * supporter withdrew also has no incoming edges, and treating those two alike would turn withdrawing the
     * final edge into a promotion to root instead of an abandonment.
     */
    private boolean root;
    /**
     * Seconds of grace left before an abandoned player is removed, or {@link #NO_GRACE} when no clock is
     * running.
     *
     * <p>This is stored rather than derived from a deadline timestamp because the clock counts <em>playtime</em>,
     * not wall-clock time: it only ticks down while the player is online. A deadline would keep running while
     * they were logged off, so somebody abandoned overnight would come back already expired.
     */
    private int graceSecondsRemaining = NO_GRACE;

    public SponsorEntry(UUID uuid, String lastKnownName, long invitedAt, long acceptedAt, SponsorStatus status) {
        this(uuid, lastKnownName, invitedAt, acceptedAt, status, false);
    }

    public SponsorEntry(UUID uuid, String lastKnownName, long invitedAt, long acceptedAt, SponsorStatus status,
            boolean root) {
        this(uuid, lastKnownName, invitedAt, acceptedAt, status, root, NO_GRACE);
    }

    public SponsorEntry(UUID uuid, String lastKnownName, long invitedAt, long acceptedAt, SponsorStatus status,
            boolean root, int graceSecondsRemaining) {
        this.graceSecondsRemaining = graceSecondsRemaining;
        this.root = root;
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.lastKnownName = lastKnownName;
        this.invitedAt = invitedAt;
        this.acceptedAt = acceptedAt;
        this.status = Objects.requireNonNull(status, "status");
    }

    public UUID getUuid() {
        return this.uuid;
    }

    public String getLastKnownName() {
        return this.lastKnownName;
    }

    void setLastKnownName(String lastKnownName) {
        this.lastKnownName = lastKnownName;
    }

    /** Epoch millis at which this player was first brought onto the server. */
    public long getInvitedAt() {
        return this.invitedAt;
    }

    void setInvitedAt(long invitedAt) {
        this.invitedAt = invitedAt;
    }

    /** Epoch millis of the first successful join, or {@code 0} while still {@link SponsorStatus#PENDING}. */
    public long getAcceptedAt() {
        return this.acceptedAt;
    }

    void setAcceptedAt(long acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    /** Whether this player anchors the graph. See the field comment: this is stored, never inferred. */
    public boolean isRoot() {
        return this.root;
    }

    void setRoot(boolean root) {
        this.root = root;
    }

    /** Seconds of grace left, or {@link #NO_GRACE} if no clock is running. */
    public int getGraceSecondsRemaining() {
        return this.graceSecondsRemaining;
    }

    void setGraceSecondsRemaining(int seconds) {
        this.graceSecondsRemaining = seconds;
    }

    public boolean hasGraceClock() {
        return this.graceSecondsRemaining >= 0;
    }

    public SponsorStatus getStatus() {
        return this.status;
    }

    void setStatus(SponsorStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Display helper: the last known name, falling back to the UUID for entries that never joined. */
    public String displayName() {
        return this.lastKnownName == null || this.lastKnownName.isBlank() ? this.uuid.toString() : this.lastKnownName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SponsorEntry entry && this.uuid.equals(entry.uuid);
    }

    @Override
    public int hashCode() {
        return this.uuid.hashCode();
    }

    @Override
    public String toString() {
        return "SponsorEntry[" + this.uuid + " '" + this.lastKnownName + "' " + this.status + "]";
    }
}
