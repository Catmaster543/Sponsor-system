package com.fiskerz.sponsor_system.bootstrap;

/**
 * Whether the sponsorship tree has a root yet.
 *
 * <p>This is the whole answer to the chicken-and-egg problem: on a brand new server the whitelist is on and empty, so
 * nobody can join, so nobody can ever be invited. Vanilla checks the whitelist in
 * {@code PlayerList#canPlayerLogin(SocketAddress, GameProfile)}, which runs during login before any NeoForge event is
 * fired, so a rejected player cannot be let back in by any mod without a mixin. The fix is to invert the problem: do
 * not turn the whitelist on until a root exists.
 */
public enum ServerState {
    /** No root yet. The whitelist is deliberately OFF and the next eligible player to join claims the tree. */
    BOOTSTRAP,
    /** A root exists. The whitelist is enforced normally and bootstrap can never be re-entered. */
    ESTABLISHED
}
