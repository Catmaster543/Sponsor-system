package com.fiskerz.sponsor_system.graph;

import java.util.Objects;
import java.util.UUID;

/**
 * One player backing another: {@code from} supports {@code to}.
 *
 * <p>Edges are the only record of responsibility. A player with several incoming edges has several supporters, and all
 * of them are equally answerable for what that player does.
 *
 * <p>At most one edge exists for any {@code (from, to)} pair, so the pair is the identity; {@link #kind} and
 * {@link #createdAt} are attributes of that relationship rather than part of its identity.
 */
public record SupportEdge(UUID from, UUID to, EdgeKind kind, long createdAt) {
    public SupportEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
    }

    /** The same relationship, recorded as a different kind. Used when a sponsorship is promoted to primary. */
    public SupportEdge withKind(EdgeKind newKind) {
        return new SupportEdge(this.from, this.to, newKind, this.createdAt);
    }

    public boolean isPrimary() {
        return this.kind == EdgeKind.PRIMARY;
    }

    /** How long this edge has existed, in milliseconds, as of {@code now}. */
    public long ageMillis(long now) {
        return now - this.createdAt;
    }
}
