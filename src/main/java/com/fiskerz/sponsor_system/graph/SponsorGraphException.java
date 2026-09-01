package com.fiskerz.sponsor_system.graph;

import java.util.UUID;

/**
 * Thrown when a graph mutation is refused. Carries a machine-readable {@link Reason} so the command layer can turn it
 * into a translated chat message; the graph itself never builds user-facing text.
 */
public class SponsorGraphException extends RuntimeException {
    private final Reason reason;
    private final UUID subject;

    public SponsorGraphException(Reason reason) {
        this(reason, null);
    }

    public SponsorGraphException(Reason reason, UUID subject) {
        super(reason.name() + (subject == null ? "" : " (" + subject + ")"));
        this.reason = reason;
        this.subject = subject;
    }

    public Reason getReason() {
        return this.reason;
    }

    /** The UUID the failure is about, when one is relevant. */
    public UUID getSubject() {
        return this.subject;
    }

    public enum Reason {
        /** A player tried to support themselves. */
        SELF_SUPPORT,
        /** The supporting player is not part of the graph, so they cannot back anyone. */
        SUPPORTER_NOT_IN_GRAPH,
        /** The supporting player was removed by an operator and can no longer back anyone. */
        SUPPORTER_REVOKED,
        /** {@code /invite} was used on someone who is already in the graph; they need {@code /sponsor}. */
        ALREADY_IN_GRAPH,
        /** {@code /sponsor} was used on someone who is not in the graph yet; they need {@code /invite}. */
        NOT_IN_GRAPH,
        /** This exact supporter already backs this exact player. */
        DUPLICATE_EDGE,
        /** The supporter has no support tickets left. */
        NO_TICKETS_LEFT,
        /** There is no edge from this supporter to this player to withdraw. */
        NO_SUCH_EDGE,
        /** A sponsorship cannot be withdrawn yet; it has not existed for the configured minimum. */
        SPONSORSHIP_TOO_YOUNG
    }
}
