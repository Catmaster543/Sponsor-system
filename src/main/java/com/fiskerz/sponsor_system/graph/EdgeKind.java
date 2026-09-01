package com.fiskerz.sponsor_system.graph;

/**
 * Why a support edge exists.
 *
 * <p><strong>This carries no responsibility semantics.</strong> Every supporter of a player is equally answerable for
 * that player, whoever brought them in first. The kind exists for exactly two reasons: to record who originally got a
 * player onto the server, and to give {@code /invitetree} a skeleton to draw. Never write a message that implies a
 * PRIMARY supporter is more or less on the hook than a SPONSORSHIP one.
 */
public enum EdgeKind {
    /** Created by {@code /invite}. At most one may point at any player; this is the edge that whitelisted them. */
    PRIMARY,
    /** Created by {@code /sponsor}. Any number may point at a player. Grants no access, only support. */
    SPONSORSHIP;

    public static EdgeKind byName(String name, EdgeKind fallback) {
        if (name == null) {
            return fallback;
        }
        for (EdgeKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return fallback;
    }
}
