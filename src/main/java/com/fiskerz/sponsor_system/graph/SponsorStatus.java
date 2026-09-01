package com.fiskerz.sponsor_system.graph;

/**
 * Lifecycle of a player in the support graph. No Minecraft imports, so the graph stays unit testable.
 */
public enum SponsorStatus {
    /** Invited and whitelisted, but has never joined the server yet. */
    PENDING,
    /** Has joined at least once, and still has support. */
    ACTIVE,
    /** Removed by an operator. Not whitelisted. */
    REVOKED,
    /**
     * Every supporter is gone, or every supporter has themselves lost support.
     *
     * <p>As of this round an abandoned player stays whitelisted and keeps playing; losing support is recorded and
     * announced but not yet enforced. The grace clock that acts on this is a later round.
     */
    ABANDONED;

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

    /**
     * Whether this player should hold a whitelist entry.
     *
     * <p>{@link #ABANDONED} counts as live: they have lost their backing but have not lost access, which is exactly
     * the state this round leaves them in.
     */
    public boolean isLive() {
        return this != REVOKED;
    }
}
