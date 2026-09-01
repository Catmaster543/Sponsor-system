package com.fiskerz.sponsor_system.graph;

/**
 * How the mod decides whether a player still has support.
 */
public enum SupportModel {
    /**
     * A player is supported if they have no supporters of their own (they are a root), or if at least one of their
     * supporters is themselves supported. That is reachability from the roots across the edge set.
     */
    REACHABILITY,
    /**
     * A player is supported if they have at least one incoming edge at all, whatever the state of whoever wrote it.
     *
     * <p>This is deliberately weaker, and it has a hole: two players can sponsor each other and hold each other up
     * forever after everyone upstream withdraws. Nobody can then remove them. Only choose this if that is what you
     * want.
     */
    DIRECT;

    public static SupportModel byName(String name, SupportModel fallback) {
        if (name == null) {
            return fallback;
        }
        for (SupportModel model : values()) {
            if (model.name().equalsIgnoreCase(name)) {
                return model;
            }
        }
        return fallback;
    }
}
