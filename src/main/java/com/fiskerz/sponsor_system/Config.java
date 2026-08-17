package com.fiskerz.sponsor_system;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-scoped configuration, registered as {@link net.neoforged.fml.config.ModConfig.Type#SERVER} so it lives with
 * the world/server rather than with the client installation.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FORCE_WHITELIST_ON = BUILDER
            .comment("Turn the vanilla whitelist on at startup if it is off.",
                    "The sponsorship tree only controls access while the whitelist is enforced, so leaving this off",
                    "means invites are recorded but nothing is actually gated.")
            .translation("sponsorsystem.configuration.forceWhitelistOn")
            .define("forceWhitelistOn", true);

    public static final ModConfigSpec.IntValue MAX_INVITES_PER_PLAYER = BUILDER
            .comment("How many live invites one player may hold. -1 means unlimited. Operators are exempt.",
                    "Revoked invitees do not count against the allowance.")
            .translation("sponsorsystem.configuration.maxInvitesPerPlayer")
            .defineInRange("maxInvitesPerPlayer", -1, -1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MIN_PLAYTIME_MINUTES_TO_INVITE = BUILDER
            .comment("Minutes of playtime a player needs before they may invite anyone. 0 disables the check.",
                    "Operators are exempt.")
            .translation("sponsorsystem.configuration.minPlaytimeMinutesToInvite")
            .defineInRange("minPlaytimeMinutesToInvite", 0, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue INVITE_COOLDOWN_MINUTES = BUILDER
            .comment("Minutes a player must wait between invites. 0 disables the cooldown. Operators are exempt.")
            .translation("sponsorsystem.configuration.inviteCooldownMinutes")
            .defineInRange("inviteCooldownMinutes", 0, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PENDING_INVITE_EXPIRY_HOURS = BUILDER
            .comment("Hours before an invite that was never used expires and is unwhitelisted. 0 means never.",
                    "Checked at server start and once an hour thereafter.")
            .translation("sponsorsystem.configuration.pendingInviteExpiryHours")
            .defineInRange("pendingInviteExpiryHours", 0, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue CASCADE_ON_REVOKE = BUILDER
            .comment("true: revoking someone also revokes everyone they invited, and everyone below them.",
                    "false: only that player is revoked and their invitees are re-parented onto their sponsor.")
            .translation("sponsorsystem.configuration.cascadeOnRevoke")
            .define("cascadeOnRevoke", true);

    public static final ModConfigSpec.BooleanValue ANNOUNCE_INVITES = BUILDER
            .comment("Broadcast invites and revocations to everyone online. Visible sponsorship is the point of the",
                    "system, so this defaults to on.")
            .translation("sponsorsystem.configuration.announceInvites")
            .define("announceInvites", true);

    public static final ModConfigSpec.BooleanValue ALLOW_OFFLINE_MODE_UUIDS = BUILDER
            .comment("On an offline-mode (cracked) server, generate the offline UUID for an invited name instead of",
                    "asking Mojang about it. Has no effect while the server is in online mode.",
                    "WARNING: offline UUIDs are derived from the name alone, so anyone can connect using an invited",
                    "name. Only enable this if you already accept that risk.")
            .translation("sponsorsystem.configuration.allowOfflineModeUuids")
            .define("allowOfflineModeUuids", false);

    public static final ModConfigSpec.BooleanValue PUBLIC_INVITE_TREE = BUILDER
            .comment("Whether players may inspect other players' invites and subtrees. Operators always may.")
            .translation("sponsorsystem.configuration.publicInviteTree")
            .define("publicInviteTree", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
