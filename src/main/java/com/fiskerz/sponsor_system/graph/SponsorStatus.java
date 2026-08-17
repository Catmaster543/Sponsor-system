package com.fiskerz.sponsor_system.graph;

/**
 * Lifecycle of a single sponsorship. Deliberately free of Minecraft imports so the graph can be unit tested.
 */
public enum SponsorStatus {
    /** Invited and whitelisted, but has never joined the server yet. */
    PENDING,
    /** Has joined at least once. */
    ACTIVE,
    /** Sponsorship was withdrawn. Kept in the file as an audit trail; not whitelisted. */
    REVOKED;

    public static SponsorStatus byName(String name, SponsorStatus fallback) {
        if (name == null) {
            return fallback;
        }
        for (SponsorStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return fallback;
    }

    public boolean isLive() {
        return this != REVOKED;
    }
}
