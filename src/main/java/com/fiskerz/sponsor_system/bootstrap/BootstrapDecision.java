package com.fiskerz.sponsor_system.bootstrap;

/**
 * The outcome of asking whether a joining player may claim the empty tree.
 *
 * <p>Modelled as data so the decision can be unit tested without a running game, and so the caller owns every
 * user-facing message.
 */
public enum BootstrapDecision {
    /** This player may become the root. */
    CLAIM,
    /** A root already exists. Nobody may claim; this is the "second player in the same tick" path. */
    ALREADY_ESTABLISHED,
    /** {@code bootstrap.enabled} is off, so an empty tree is a deliberate hard lockout. */
    DISABLED,
    /** {@code bootstrap.restrictToName} is set and this is not that player. */
    WRONG_NAME,
    /** {@code bootstrap.restrictToLoopback} is on and this connection is not local. */
    NOT_LOOPBACK;

    public boolean isClaim() {
        return this == CLAIM;
    }
}
