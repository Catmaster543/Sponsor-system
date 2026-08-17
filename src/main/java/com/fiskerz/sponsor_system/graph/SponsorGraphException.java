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

    /** The UUID the failure is about, when one is relevant (e.g. the existing sponsor on a duplicate invite). */
    public UUID getSubject() {
        return this.subject;
    }

    public enum Reason {
        /** A player tried to invite themselves. */
        SELF_INVITE,
        /** The inviting player is not part of the tree, so they cannot sponsor anyone. */
        SPONSOR_NOT_IN_GRAPH,
        /** The inviting player's own sponsorship was revoked, so they cannot vouch for anyone else. */
        SPONSOR_REVOKED,
        /** The target already has a live sponsorship. {@link #getSubject()} is the existing sponsor. */
        ALREADY_SPONSORED,
        /** The operation would make someone their own ancestor. */
        WOULD_CREATE_CYCLE,
        /** The sponsor has used up their configured invite allowance. */
        INVITE_LIMIT_REACHED,
        /** The referenced player has no entry in the tree. */
        NOT_IN_GRAPH
    }
}
