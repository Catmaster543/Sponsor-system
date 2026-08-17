package com.fiskerz.sponsor_system.graph;

import java.util.Objects;
import java.util.UUID;

/**
 * One node of the sponsorship tree.
 *
 * <p>The UUID is the only identity key. {@link #getLastKnownName()} exists purely so admins can read
 * {@code sponsors.json} and chat messages can show something human; it is refreshed on every login and must never
 * be used to look an entry up.
 */
public final class SponsorEntry {
    private final UUID uuid;
    private String lastKnownName;
    private UUID sponsor;
    private long invitedAt;
    private long acceptedAt;
    private SponsorStatus status;

    public SponsorEntry(UUID uuid, String lastKnownName, UUID sponsor, long invitedAt, long acceptedAt, SponsorStatus status) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.lastKnownName = lastKnownName;
        this.sponsor = sponsor;
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

    public void setLastKnownName(String lastKnownName) {
        this.lastKnownName = lastKnownName;
    }

    /** The sponsor's UUID, or {@code null} when this entry is a root of the tree. */
    public UUID getSponsor() {
        return this.sponsor;
    }

    void setSponsor(UUID sponsor) {
        this.sponsor = sponsor;
    }

    public boolean isRoot() {
        return this.sponsor == null;
    }

    /** Epoch millis at which the invite was issued. */
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

    public void setAcceptedAt(long acceptedAt) {
        this.acceptedAt = acceptedAt;
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
        return "SponsorEntry[" + this.uuid + " '" + this.lastKnownName + "' sponsor=" + this.sponsor + " " + this.status + "]";
    }
}
