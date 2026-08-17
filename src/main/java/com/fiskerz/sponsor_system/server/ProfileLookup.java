package com.fiskerz.sponsor_system.server;

import com.mojang.authlib.GameProfile;

/**
 * The outcome of resolving a typed-in username to a {@link GameProfile}.
 *
 * <p>Failure is a normal outcome here — Mojang's API goes down, gets rate limited, or simply does not know the name —
 * so it is modelled as data rather than an exception, and the command layer turns each case into its own message.
 */
public record ProfileLookup(Result result, GameProfile profile) {
    public enum Result {
        /** The name resolved. {@link #profile()} is non-null. */
        FOUND,
        /** The lookup worked but no such account exists. */
        NOT_FOUND,
        /** The name is not a syntactically valid Minecraft username, so no lookup was attempted. */
        INVALID_NAME,
        /** Mojang could not be reached, or the server is shutting down. Retrying later may work. */
        UNAVAILABLE,
        /** The server is in offline mode and {@code allowOfflineModeUuids} is off, so any UUID we picked would be wrong. */
        OFFLINE_MODE_BLOCKED
    }

    public static ProfileLookup found(GameProfile profile) {
        return new ProfileLookup(Result.FOUND, profile);
    }

    public static ProfileLookup of(Result result) {
        return new ProfileLookup(result, null);
    }

    public boolean isFound() {
        return this.result == Result.FOUND;
    }
}
