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

    public static final ModConfigSpec.IntValue MIN_PLAYTIME_MINUTES_TO_INVITE = BUILDER
            .comment("Minutes of playtime a player needs before they may back anyone. 0 disables the check.",
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

    public static final ModConfigSpec.BooleanValue ANNOUNCE_INVITES = BUILDER
            .comment("Broadcast invites, sponsorships and withdrawals to everyone online. Visible support is the point",
                    "of the system, so this defaults to on.")
            .translation("sponsorsystem.configuration.announceInvites")
            .define("announceInvites", true);

    public static final ModConfigSpec.BooleanValue ALLOW_OFFLINE_MODE_UUIDS = BUILDER
            .comment("On an offline-mode (cracked) server, generate the offline UUID for an invited name instead of",
                    "asking Mojang about it. Has no effect while the server is in online mode.",
                    "WARNING: offline UUIDs are derived from the name alone, so anyone can connect using an invited",
                    "name. Only enable this if you already accept that risk.")
            .translation("sponsorsystem.configuration.allowOfflineModeUuids")
            .define("allowOfflineModeUuids", false);

    // -----------------------------------------------------------------------------------------------------------
    // The support model
    // -----------------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.ConfigValue<String> SUPPORT_MODEL = BUILDER
            .comment("How the mod decides whether a player still has support. One of REACHABILITY or DIRECT.",
                    "",
                    "REACHABILITY (default): a player is supported if they are a root, or if at least one of their",
                    "supporters is themselves supported. Support flows outwards from the roots.",
                    "",
                    "DIRECT: a player is supported if anyone at all backs them, whatever state that supporter is in.",
                    "This has a hole, and it is not a small one: two players can sponsor each other and prop each",
                    "other up forever. Their inviter withdraws, they hold each other in place, and nobody upstream can",
                    "do anything about it - which defeats the entire premise of the mod. DIRECT exists only for",
                    "servers that have decided they want the looser behaviour anyway.")
            .translation("sponsorsystem.configuration.supportModel")
            .define("supportModel", "REACHABILITY",
                    value -> value instanceof String name
                            && ("REACHABILITY".equalsIgnoreCase(name) || "DIRECT".equalsIgnoreCase(name)));

    public static final ModConfigSpec.IntValue SPONSORSHIP_MIN_DURATION_MINUTES = BUILDER
            .comment("How long a sponsorship must exist before the player who wrote it may withdraw it.",
                    "Operators bypass this. 0 disables it.",
                    "This is an anti-flap measure. Restoring support is meant to be a commitment, not a switch you",
                    "can flick on and off; without a minimum, a player could sponsor and immediately withdraw over",
                    "and over.")
            .translation("sponsorsystem.configuration.sponsorshipMinDurationMinutes")
            .defineInRange("sponsorshipMinDurationMinutes", 10, 0, Integer.MAX_VALUE);

    // -----------------------------------------------------------------------------------------------------------
    // The abandonment clock
    // -----------------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue ABANDONED_GRACE_MINUTES = BUILDER
            .comment("How long a player who has lost all support may keep playing before they are removed.",
                    "",
                    "This is PLAYTIME, not wall-clock time: it counts down only while they are online, and it is",
                    "saved with their entry, so logging out pauses it and a restart does not reset it. Somebody who",
                    "logs off with 12 minutes left comes back to 12 minutes left.",
                    "",
                    "Anyone backing them again clears the clock entirely; their next abandonment starts from full.",
                    "0 removes them the moment they lose support, with no grace at all.")
            .translation("sponsorsystem.configuration.abandonedGraceMinutes")
            .defineInRange("abandonedGraceMinutes", 30, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue ABANDONED_COUNTDOWN_ENABLED = BUILDER
            .comment("Show abandoned players a live countdown on the action bar, above the hotbar.",
                    "Turn this off for chat warnings only. The action bar is shared with vanilla uses such as held",
                    "item names and jukebox tracks, and with other mods, so on a busy server it may be contested.")
            .translation("sponsorsystem.configuration.abandonedCountdownEnabled")
            .define("abandonedCountdownEnabled", true);

    // -----------------------------------------------------------------------------------------------------------
    // Support tickets
    // -----------------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue MAX_SUPPORT_TICKETS_PER_PLAYER = BUILDER
            .comment("How many players one person may back at a time, counting invites and sponsorships together.",
                    "-1 means unlimited. Withdrawing support refunds the ticket.",
                    "One shared pool is the default on purpose: it is the shared scarcity that forces a player to",
                    "choose who is actually worth backing.")
            .translation("sponsorsystem.configuration.maxSupportTicketsPerPlayer")
            .defineInRange("maxSupportTicketsPerPlayer", 10, -1, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue SEPARATE_INVITE_AND_SPONSOR_BUDGETS = BUILDER
            .comment("Split the single pool above into two independent ones, so spending an invite does not reduce",
                    "how many people you may sponsor. Off by default.")
            .translation("sponsorsystem.configuration.separateInviteAndSponsorBudgets")
            .define("separateInviteAndSponsorBudgets", false);

    public static final ModConfigSpec.IntValue MAX_INVITES_PER_PLAYER = BUILDER
            .comment("Invite pool size. Only used when separateInviteAndSponsorBudgets is true. -1 means unlimited.")
            .translation("sponsorsystem.configuration.maxInvitesPerPlayer")
            .defineInRange("maxInvitesPerPlayer", 10, -1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_SPONSORSHIPS_PER_PLAYER = BUILDER
            .comment("Sponsorship pool size. Only used when separateInviteAndSponsorBudgets is true. -1 means unlimited.")
            .translation("sponsorsystem.configuration.maxSponsorshipsPerPlayer")
            .defineInRange("maxSponsorshipsPerPlayer", 10, -1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue UNLIMITED_TICKETS_PERMISSION_LEVEL = BUILDER
            .comment("Players at or above this permission level have unlimited tickets. The root always does.",
                    "Set above 4 to make nobody exempt.")
            .translation("sponsorsystem.configuration.unlimitedTicketsPermissionLevel")
            .defineInRange("unlimitedTicketsPermissionLevel", 3, 0, 5);

    // -----------------------------------------------------------------------------------------------------------
    // Founder bootstrap, grouped under a [bootstrap] table in the TOML.
    //
    // A brand new server has an empty tree, so with the whitelist on nobody can join and nobody can ever be invited.
    // Vanilla rejects unwhitelisted logins inside PlayerList#canPlayerLogin, which runs before any event this mod
    // could hook, so the only sound fix is to not turn the whitelist on until a root exists.
    //
    // These are declared inside one static block rather than as chained field initialisers because the push/pop pair
    // has to bracket exactly these four keys: doing it that way keeps the grouping impossible to break by moving a
    // field around later.
    // -----------------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue BOOTSTRAP_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BOOTSTRAP_RESTRICT_TO_NAME;
    public static final ModConfigSpec.BooleanValue BOOTSTRAP_RESTRICT_TO_LOOPBACK;
    public static final ModConfigSpec.BooleanValue BOOTSTRAP_OP_FOUNDER;

    static {
        BUILDER.comment("How the very first player claims the root of the tree on a brand new server.")
                .push("bootstrap");

        BOOTSTRAP_ENABLED = BUILDER
                .comment("Let the first player to join an EMPTY tree claim it as the root.",
                        "While waiting for that player the whitelist is held OFF, so the server is open to anyone who",
                        "knows the address. Use restrictToName or restrictToLoopback below to close that window.",
                        "If this is false, an empty tree is a hard lockout: nobody can join at all, and the only way",
                        "back in is to run '/op <name>' from the server console (operators bypass the whitelist) and",
                        "then '/sponsorship adopt <name>'.")
                .translation("sponsorsystem.configuration.bootstrap.enabled")
                .define("enabled", true);

        BOOTSTRAP_RESTRICT_TO_NAME = BUILDER
                .comment("If set, only this username may claim the root. Strongly recommended on a public server:",
                        "it closes the open window almost entirely. Empty means anyone may claim.",
                        "Matched ignoring case, so the casing you type here does not have to be exact.")
                .translation("sponsorsystem.configuration.bootstrap.restrictToName")
                .define("restrictToName", "");

        BOOTSTRAP_RESTRICT_TO_LOOPBACK = BUILDER
                .comment("If true, only a connection from 127.0.0.1 or ::1 may claim the root. Useful in development,",
                        "and safe on any server you can log into locally. A single-player or LAN-host connection",
                        "counts as local.")
                .translation("sponsorsystem.configuration.bootstrap.restrictToLoopback")
                .define("restrictToLoopback", false);

        BOOTSTRAP_OP_FOUNDER = BUILDER
                .comment("Also grant the founder operator status when they claim the root.",
                        "Off by default: being the root of the tree is not the same thing as being an operator, and",
                        "conflating them should be a deliberate choice.")
                .translation("sponsorsystem.configuration.bootstrap.opFounder")
                .define("opFounder", false);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
