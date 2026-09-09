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
     * <p>An abandoned player is still whitelisted and still playing: they are on a grace period, counted in playtime,
     * and they get it back the moment anyone backs them again.
     */
    ABANDONED,
    /**
     * The grace period ran out with nobody having stepped in. Not whitelisted.
     *
     * <p>This is the one status a player reaches by nothing but the passage of time. They may come back if somebody
     * invites them again.
     */
    EXPIRED;

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
     * <p>{@link #ABANDONED} counts as live: they have lost their backing but not yet their access, which is exactly
     * what the grace period is.
     *
     * <p>This is also the single test the support model uses to decide who can carry support onward, which is what
     * makes an expiry cascade automatic: an expired player stops being live, so nothing flows through them, so
     * everyone who depended on them is abandoned by the very next recompute. There is no separate cascade routine and
     * there must not be one.
     */
    public boolean isLive() {
        return this != REVOKED && this != EXPIRED;
    }

    /** Whether a grace countdown makes sense for this status. */
    public boolean isOnGracePeriod() {
        return this == ABANDONED;
    }
}
