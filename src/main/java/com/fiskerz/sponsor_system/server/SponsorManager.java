package com.fiskerz.sponsor_system.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fiskerz.sponsor_system.Config;
import com.fiskerz.sponsor_system.Sponsorsystem;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorGraph;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.store.SponsorStore;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * Owns the sponsorship tree for a running server and keeps it in step with the vanilla whitelist.
 *
 * <p>Threading: everything here runs on the server thread. The single exception is the Mojang profile lookup in
 * {@link #resolveProfile}, which runs on Minecraft's background executor and hops back via
 * {@link MinecraftServer#execute} before the callback touches any state.
 */
public final class SponsorManager {
    /** Minecraft usernames: 3-16 characters of letters, digits and underscore. Checked before any network call. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    @Nullable
    private static SponsorManager instance;

    private final MinecraftServer server;
    private final SponsorGraph graph = new SponsorGraph();
    private final SponsorStore store;
    private final WhitelistBridge whitelist;
    private long ticksUntilExpirySweep = TICKS_PER_HOUR;

    private SponsorManager(MinecraftServer server) {
        this.server = server;
        this.store = new SponsorStore(server.getServerDirectory().resolve("sponsors.json"));
        this.whitelist = new WhitelistBridge(server);
    }

    /** The manager for the running server, or {@code null} when no server is running. */
    @Nullable
    public static SponsorManager get() {
        return instance;
    }

    public static SponsorManager start(MinecraftServer server) {
        SponsorManager manager = new SponsorManager(server);
        instance = manager;
        manager.loadAndReconcile();
        return manager;
    }

    public static void stop() {
        instance = null;
    }

    public SponsorGraph graph() {
        return this.graph;
    }

    public WhitelistBridge whitelist() {
        return this.whitelist;
    }

    public MinecraftServer server() {
        return this.server;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reads {@code sponsors.json} and makes the whitelist agree with it: live members that lost their whitelist entry
     * get it back, revoked members that still have one lose it, and whitelisted players the tree has never heard of
     * are reported as orphans and left exactly as they are.
     *
     * <p>Orphans are never removed automatically. On a server that installs this mod for the first time every existing
     * whitelist entry is an orphan, and deleting them would lock the owner out of their own server.
     */
    private void loadAndReconcile() {
        List<String> warnings = new ArrayList<>();
        try {
            List<SponsorEntry> loaded = this.store.load(warnings);
            warnings.addAll(this.graph.replaceAll(loaded));
            Sponsorsystem.LOGGER.info("Loaded {} sponsorship entries from {}", this.graph.size(), this.store.getFile());
        } catch (IOException exception) {
            Sponsorsystem.LOGGER.error("Could not read {}. Starting with an EMPTY sponsorship tree; the file has been "
                    + "left untouched so you can fix it and run /sponsorship reload.", this.store.getFile(), exception);
            return;
        }
        warnings.forEach(Sponsorsystem.LOGGER::warn);

        if (Config.FORCE_WHITELIST_ON.get() && !this.whitelist.isEnforced()) {
            this.whitelist.setEnforced(true);
            Sponsorsystem.LOGGER.warn("The whitelist was OFF and forceWhitelistOn is true, so it has been turned ON. "
                    + "Anyone not in the sponsorship tree can no longer join.");
        } else if (!this.whitelist.isEnforced()) {
            Sponsorsystem.LOGGER.warn("The whitelist is OFF and forceWhitelistOn is false. Invites will be recorded "
                    + "but nothing is actually gated - anyone can join.");
        }

        this.reconcileWithWhitelist();

        if (this.graph.isEmpty()) {
            Sponsorsystem.LOGGER.info("The sponsorship tree is empty. Operators bypass the whitelist, so an operator "
                    + "can still join and bootstrap the tree: the first operator to run /invite is adopted as the root "
                    + "automatically, or you can run /sponsorship adopt <player> to name a root explicitly.");
        }
        this.sweepExpiredInvites();
    }

    /** Re-applies the tree to the whitelist and logs anything that was out of step. */
    public void reconcileWithWhitelist() {
        Set<UUID> whitelisted = new HashSet<>();
        List<GameProfile> whitelistEntries = this.whitelist.entries();
        whitelistEntries.forEach(profile -> whitelisted.add(profile.getId()));

        int added = 0;
        int removed = 0;
        for (SponsorEntry entry : this.graph.entries()) {
            boolean isWhitelisted = whitelisted.contains(entry.getUuid());
            if (entry.getStatus().isLive() && !isWhitelisted) {
                this.whitelist.add(new GameProfile(entry.getUuid(), entry.displayName()));
                added++;
            } else if (!entry.getStatus().isLive() && isWhitelisted) {
                this.whitelist.remove(entry.getUuid(), entry.getLastKnownName());
                removed++;
            }
        }
        if (added > 0) {
            Sponsorsystem.LOGGER.warn("Re-whitelisted {} sponsored player(s) that were missing from whitelist.json.", added);
        }
        if (removed > 0) {
            Sponsorsystem.LOGGER.warn("Removed {} revoked player(s) that were still in whitelist.json.", removed);
        }

        List<String> orphans = new ArrayList<>();
        for (GameProfile profile : whitelistEntries) {
            if (!this.graph.contains(profile.getId())) {
                orphans.add(profile.getName() + " (" + profile.getId() + ")");
            }
        }
        if (!orphans.isEmpty()) {
            Sponsorsystem.LOGGER.warn("{} whitelisted player(s) have no sponsor and are NOT part of the tree. They stay "
                    + "whitelisted until an operator runs /sponsorship adopt <player> [sponsor]: {}",
                    orphans.size(), String.join(", ", orphans));
        }
    }

    /** Re-reads {@code sponsors.json} from disk, discarding the in-memory tree. @return warnings worth showing */
    public List<String> reload() throws IOException {
        List<String> warnings = new ArrayList<>();
        List<SponsorEntry> loaded = this.store.load(warnings);
        warnings.addAll(this.graph.replaceAll(loaded));
        this.reconcileWithWhitelist();
        return warnings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Persists the tree.
     *
     * @return {@code true} on success. On failure the in-memory tree is rolled back to whatever is on disk, so a
     *         caller that gets {@code false} can abort without leaving memory and disk disagreeing.
     */
    public boolean save() {
        try {
            this.store.save(this.graph.entries());
            return true;
        } catch (IOException exception) {
            Sponsorsystem.LOGGER.error("Could not write {}; rolling the sponsorship tree back to the last saved state.",
                    this.store.getFile(), exception);
            try {
                List<String> warnings = new ArrayList<>();
                this.graph.replaceAll(this.store.load(warnings));
            } catch (IOException reloadFailure) {
                Sponsorsystem.LOGGER.error("Rollback failed as well; the in-memory tree may be ahead of {}.",
                        this.store.getFile(), reloadFailure);
            }
            return false;
        }
    }

    public void saveOnShutdown() {
        if (this.save()) {
            Sponsorsystem.LOGGER.info("Saved {} sponsorship entries to {}", this.graph.size(), this.store.getFile());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Profile resolution
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Turns a typed-in username into a {@link GameProfile}, then hands the result to {@code callback} <em>on the
     * server thread</em>.
     *
     * <p>On an offline-mode server the answer is computed locally rather than asked of Mojang: players there connect
     * with the name-derived offline UUID, so a real Mojang UUID would whitelist an account that can never join.
     */
    public void resolveProfile(String name, Consumer<ProfileLookup> callback) {
        if (!VALID_NAME.matcher(name).matches()) {
            callback.accept(ProfileLookup.of(ProfileLookup.Result.INVALID_NAME));
            return;
        }

        if (!this.server.usesAuthentication()) {
            if (!Config.ALLOW_OFFLINE_MODE_UUIDS.get()) {
                callback.accept(ProfileLookup.of(ProfileLookup.Result.OFFLINE_MODE_BLOCKED));
            } else {
                callback.accept(ProfileLookup.found(UUIDUtil.createOfflineProfile(name)));
            }
            return;
        }

        CompletableFuture<Optional<GameProfile>> future;
        try {
            future = this.server.getProfileCache().getAsync(name);
        } catch (IllegalStateException exception) {
            // getAsync throws if the cache has no executor, which happens once the server is shutting down.
            Sponsorsystem.LOGGER.warn("Profile lookup for '{}' was refused; the server is not accepting lookups.", name);
            callback.accept(ProfileLookup.of(ProfileLookup.Result.UNAVAILABLE));
            return;
        }

        future.whenComplete((profile, error) -> {
            ProfileLookup lookup;
            if (error != null) {
                Sponsorsystem.LOGGER.warn("Profile lookup for '{}' failed.", name, error);
                lookup = ProfileLookup.of(ProfileLookup.Result.UNAVAILABLE);
            } else if (profile == null || profile.isEmpty()) {
                lookup = ProfileLookup.of(ProfileLookup.Result.NOT_FOUND);
            } else {
                lookup = ProfileLookup.found(profile.get());
            }
            // Back onto the server thread before anything touches the tree, the whitelist, or a player's chat.
            this.server.execute(() -> callback.accept(lookup));
        });
    }

    /** Looks a name up in the tree. Names are display data, so this is a scan and ties are broken by liveness. */
    public Optional<SponsorEntry> findByName(String name) {
        SponsorEntry fallback = null;
        for (SponsorEntry entry : this.graph.entries()) {
            if (entry.getLastKnownName() != null && entry.getLastKnownName().equalsIgnoreCase(name)) {
                if (entry.getStatus().isLive()) {
                    return Optional.of(entry);
                }
                fallback = entry;
            }
        }
        return Optional.ofNullable(fallback);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Records a sponsorship and whitelists the invitee.
     *
     * <p>Order matters: the tree is updated and saved first, and the whitelist is only touched once the save
     * succeeded. If the disk write fails the tree is rolled back and nothing is whitelisted, so the two never
     * disagree.
     *
     * @return the new entry, or empty if the save failed
     */
    public Optional<SponsorEntry> commitInvite(UUID sponsor, GameProfile invitee, int maxInvites) {
        SponsorEntry entry = this.graph.invite(sponsor, invitee.getId(), invitee.getName(), System.currentTimeMillis(), maxInvites);
        if (!this.save()) {
            return Optional.empty();
        }
        this.whitelist.add(invitee);
        return Optional.of(entry);
    }

    /** Adopts a player into the tree, whitelisting them if they were not already. */
    public Optional<SponsorEntry> commitAdopt(GameProfile profile, @Nullable UUID sponsor, SponsorStatus status) {
        SponsorEntry entry = this.graph.adopt(profile.getId(), profile.getName(), sponsor, System.currentTimeMillis(), status);
        if (!this.save()) {
            return Optional.empty();
        }
        this.whitelist.add(profile);
        return Optional.of(entry);
    }

    /**
     * Revokes a sponsorship, unwhitelists everyone affected and kicks any of them who are online.
     *
     * @param cascade whether to take the whole subtree down with the target
     * @param kickReason the message disconnected players see; it should name who caused this and why
     * @return the affected players, or empty if the save failed and nothing was changed
     */
    public Optional<List<SponsorEntry>> commitRevoke(UUID target, boolean cascade, Component kickReason) {
        Set<UUID> affected = this.graph.revoke(target, cascade, System.currentTimeMillis());
        if (!this.save()) {
            return Optional.empty();
        }

        List<SponsorEntry> entries = new ArrayList<>();
        for (UUID uuid : affected) {
            SponsorEntry entry = this.graph.get(uuid).orElse(null);
            if (entry != null) {
                entries.add(entry);
            }
            this.whitelist.remove(uuid, entry == null ? null : entry.getLastKnownName());
            this.kick(uuid, kickReason);
        }
        return Optional.of(entries);
    }

    /**
     * Disconnects a player if they are online.
     *
     * <p>Operators bypass the whitelist, so removing their entry does not remove them from the server; kicking is what
     * actually makes a revocation take effect for someone who is already connected.
     */
    public void kick(UUID uuid, Component reason) {
        ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            player.connection.disconnect(reason);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Player lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    /** Flips a PENDING invite to ACTIVE on first join and refreshes the cached name. */
    public void onPlayerLoggedIn(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!this.graph.contains(uuid)) {
            return;
        }

        String name = player.getGameProfile().getName();
        boolean renamed = this.graph.refreshName(uuid, name);
        boolean activated = this.graph.activate(uuid, System.currentTimeMillis());
        if (activated) {
            Sponsorsystem.LOGGER.info("{} joined for the first time; their sponsorship is now active.", name);
        }
        if (renamed || activated) {
            this.save();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Expiry
    // ---------------------------------------------------------------------------------------------------------------

    /** Called every server tick; the sweep itself runs at most once an hour. */
    public void tick() {
        if (Config.PENDING_INVITE_EXPIRY_HOURS.get() <= 0) {
            return;
        }
        if (--this.ticksUntilExpirySweep > 0) {
            return;
        }
        this.ticksUntilExpirySweep = TICKS_PER_HOUR;
        this.sweepExpiredInvites();
    }

    /** Revokes and unwhitelists invites that were never taken up within the configured window. */
    public void sweepExpiredInvites() {
        int hours = Config.PENDING_INVITE_EXPIRY_HOURS.get();
        if (hours <= 0) {
            return;
        }

        long cutoff = System.currentTimeMillis() - (long) hours * 60L * 60L * 1000L;
        // Collect UUIDs rather than entries: a failed save rebuilds the tree from disk, which would leave any entry
        // objects captured here pointing at a graph that no longer exists.
        List<UUID> stale = this.graph.pendingInvitedBefore(cutoff).stream().map(SponsorEntry::getUuid).toList();
        if (stale.isEmpty()) {
            return;
        }

        boolean cascade = Config.CASCADE_ON_REVOKE.get();
        Component reason = Component.translatable("sponsorsystem.kick.expired");
        for (UUID uuid : stale) {
            // Re-read the status: an earlier expiry in this same sweep may have taken this one down as part of a subtree.
            SponsorEntry entry = this.graph.get(uuid).orElse(null);
            if (entry == null || entry.getStatus() != SponsorStatus.PENDING) {
                continue;
            }
            String name = entry.displayName();
            if (this.commitRevoke(uuid, cascade, reason).isEmpty()) {
                Sponsorsystem.LOGGER.error("Could not expire the invite for {}; abandoning this sweep.", name);
                return;
            }
            Sponsorsystem.LOGGER.info("Invite for {} expired after {} hours and was withdrawn.", name, hours);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers shared by the commands
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether this profile is an operator. Operators bypass the whitelist and are exempt from invite limits. */
    public boolean isOperator(GameProfile profile) {
        return this.server.getPlayerList().isOp(profile);
    }

    /** Every name worth offering for tab completion: everyone in the tree, plus everyone on the whitelist. */
    public List<String> knownNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SponsorEntry entry : this.graph.entries()) {
            if (entry.getLastKnownName() != null && !entry.getLastKnownName().isBlank()) {
                names.add(entry.getLastKnownName());
            }
        }
        names.addAll(List.of(this.whitelist.names()));
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    /** Names of players the given player may act on with {@code /uninvite}: their own live, direct invitees. */
    public List<String> directInviteeNames(UUID sponsor) {
        List<String> names = new ArrayList<>();
        for (UUID child : this.graph.directChildren(sponsor)) {
            this.graph.get(child)
                    .filter(entry -> entry.getStatus().isLive())
                    .map(SponsorEntry::displayName)
                    .ifPresent(names::add);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Playtime in minutes, from the vanilla statistic. */
    public int playtimeMinutes(ServerPlayer player) {
        int ticks = player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
        return ticks / (20 * 60);
    }
}
