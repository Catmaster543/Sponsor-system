package com.fiskerz.sponsor_system.bootstrap;

import java.util.Locale;

/**
 * The founder-bootstrap rules, with no Minecraft imports, so every branch is unit testable without a game instance.
 *
 * <p>The caller supplies the facts (is the tree empty, what did the config say, where did this player connect from)
 * and this class decides; applying the decision — whitelisting, persisting, kicking — stays in the server layer.
 */
public final class BootstrapPolicy {
    private BootstrapPolicy() {}

    /**
     * The state a server starts in.
     *
     * <p>Bootstrap requires <em>both</em> an empty tree and an empty whitelist, which is exactly the "brand new
     * server" this feature exists for. Requiring only an empty tree would mean that installing this mod on an
     * established server — which has a populated {@code whitelist.json} but no {@code sponsors.json} yet — silently
     * turns that server's whitelist off and opens it to anyone. Such a server has an admitted population already; the
     * way in is {@code /sponsorship adopt}, not a bootstrap.
     *
     * <p>Note also what "empty tree" means: a tree whose every member has been revoked is <em>not</em> empty, because
     * revoked entries are kept as an audit trail. That is deliberate — a bootstrap you can re-enter by revoking
     * everyone would be a backdoor for turning the whitelist off on a live server.
     *
     * @param graphEmpty       whether the tree has zero entries of any status
     * @param whitelistEmpty   whether {@code whitelist.json} has zero entries
     * @param bootstrapEnabled the {@code bootstrap.enabled} setting
     */
    public static ServerState initialState(boolean graphEmpty, boolean whitelistEmpty, boolean bootstrapEnabled) {
        return graphEmpty && whitelistEmpty && bootstrapEnabled ? ServerState.BOOTSTRAP : ServerState.ESTABLISHED;
    }

    /**
     * Whether a joining player may claim the tree.
     *
     * <p>Checked in order of severity so the caller can log the most specific reason: state first (the race guard),
     * then the config gate, then the identity guards.
     *
     * @param state             the current server state, re-read at claim time rather than trusted from startup
     * @param bootstrapEnabled  the {@code bootstrap.enabled} setting
     * @param playerName        the joining player's name
     * @param restrictToName    the {@code bootstrap.restrictToName} setting; blank means "anyone"
     * @param restrictToLoopback the {@code bootstrap.restrictToLoopback} setting
     * @param loopback          whether this player's connection is local
     */
    public static BootstrapDecision evaluateClaim(ServerState state, boolean bootstrapEnabled, String playerName,
            String restrictToName, boolean restrictToLoopback, boolean loopback) {
        if (state != ServerState.BOOTSTRAP) {
            return BootstrapDecision.ALREADY_ESTABLISHED;
        }
        if (!bootstrapEnabled) {
            return BootstrapDecision.DISABLED;
        }
        if (restrictToName != null && !restrictToName.isBlank()
                && !restrictToName.trim().equalsIgnoreCase(playerName == null ? "" : playerName)) {
            return BootstrapDecision.WRONG_NAME;
        }
        if (restrictToLoopback && !loopback) {
            return BootstrapDecision.NOT_LOOPBACK;
        }
        return BootstrapDecision.CLAIM;
    }

    /** Normalises a configured name for display in logs. */
    public static String describeRestriction(String restrictToName) {
        return restrictToName == null || restrictToName.isBlank() ? "(anyone)" : restrictToName.trim();
    }

}
