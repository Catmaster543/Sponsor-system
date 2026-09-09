package com.fiskerz.sponsor_system.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
import com.fiskerz.sponsor_system.bootstrap.BootstrapDecision;
import com.fiskerz.sponsor_system.bootstrap.BootstrapPolicy;
import com.fiskerz.sponsor_system.bootstrap.ServerState;
import com.fiskerz.sponsor_system.command.Messages;
import com.fiskerz.sponsor_system.command.SponsorCommands;
import com.fiskerz.sponsor_system.graph.EdgeKind;
import com.fiskerz.sponsor_system.graph.GraceCountdown;
import com.fiskerz.sponsor_system.graph.SponsorEntry;
import com.fiskerz.sponsor_system.graph.SponsorStatus;
import com.fiskerz.sponsor_system.graph.SupportEdge;
import com.fiskerz.sponsor_system.graph.SupportGraph;
import com.fiskerz.sponsor_system.graph.SupportModel;
import com.fiskerz.sponsor_system.graph.TicketBudget;
import com.fiskerz.sponsor_system.store.SponsorStore;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * Owns the support graph for a running server and keeps it in step with the vanilla whitelist.
 *
 * <p>Threading: everything here runs on the server thread. The single exception is the Mojang profile lookup in
 * {@link #resolveProfile}, which runs on Minecraft's background executor and hops back via
 * {@link MinecraftServer#execute} before the callback touches any state.
 */
public final class SponsorManager {
    /** Minecraft usernames: 3-16 characters of letters, digits and underscore. Checked before any network call. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;
    /** The countdown updates once a second, never once a tick. */
    private static final long TICKS_PER_GRACE_TICK = 20L;
    private static final int TICK_SECONDS = 1;

    @Nullable
    private static SponsorManager instance;

    private final MinecraftServer server;
    private final SupportGraph graph = new SupportGraph();
    private final SponsorStore store;
    private final WhitelistBridge whitelist;
    private long ticksUntilExpirySweep = TICKS_PER_HOUR;
    private long ticksUntilGraceTick = TICKS_PER_GRACE_TICK;
    /**
     * Whether a root exists. Only ever moves BOOTSTRAP -> ESTABLISHED, never back: a bootstrap you can re-enter by
     * revoking everyone would be a backdoor that turns the whitelist off on a live server. Server thread only.
     */
    private ServerState state = ServerState.ESTABLISHED;

    private SponsorManager(MinecraftServer server) {
        this.server = server;
        this.store = new SponsorStore(server.getServerDirectory().resolve("sponsors.json"));
        this.whitelist = new WhitelistBridge(server);
    }

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

    public SupportGraph graph() {
        return this.graph;
    }

    public WhitelistBridge whitelist() {
        return this.whitelist;
    }

    public MinecraftServer server() {
        return this.server;
    }

    public ServerState state() {
        return this.state;
    }

    public boolean isBootstrapping() {
        return this.state == ServerState.BOOTSTRAP;
    }

    public java.nio.file.Path storePath() {
        return this.store.getFile();
    }

    /** The configured support model, falling back to the safe one if the config holds something unrecognised. */
    public SupportModel supportModel() {
        return SupportModel.byName(Config.SUPPORT_MODEL.get(), SupportModel.REACHABILITY);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------------------------------------------------

    private void loadAndReconcile() {
        List<String> warnings = new ArrayList<>();
        try {
            SponsorStore.Loaded loaded = this.store.load(warnings);
            warnings.addAll(this.graph.replaceAll(loaded.entries(), loaded.edges()));
            Sponsorsystem.LOGGER.info("Loaded {} player(s) and {} support edge(s) from {} (schema {})",
                    this.graph.size(), this.graph.edgeCount(), this.store.getFile(), loaded.schemaVersion());
        } catch (IOException exception) {
            Sponsorsystem.LOGGER.error("Could not read {}. Starting with an EMPTY support graph; the file has been "
                    + "left untouched so you can fix it and run /sponsorship reload.", this.store.getFile(), exception);
            // Deliberately stay ESTABLISHED: an unreadable file is not the same as a new server, and dropping into
            // BOOTSTRAP here would turn the whitelist off on a server that already had a graph.
            return;
        }
        warnings.forEach(Sponsorsystem.LOGGER::warn);

        boolean whitelistEmpty = this.whitelist.entries().isEmpty();
        this.state = BootstrapPolicy.initialState(this.graph.isEmpty(), whitelistEmpty, Config.BOOTSTRAP_ENABLED.get());
        if (this.state == ServerState.BOOTSTRAP) {
            this.enterBootstrap();
        } else {
            this.applyWhitelistPolicy();
            this.warnEmptyGraph(whitelistEmpty);
        }

        // One recompute at startup, so a graph edited by hand between runs is reconciled before anyone joins.
        SupportGraph.SupportChange change = this.graph.recomputeSupport(this.supportModel(), this.fullGraceSeconds());
        if (!change.abandoned().isEmpty()) {
            Sponsorsystem.LOGGER.warn("{} player(s) have no support and are marked ABANDONED: {}",
                    change.abandoned().size(), this.describe(change.abandoned()));
        }
        this.reconcileWithWhitelist();
        this.sweepExpiredInvites();

        if (this.store.isMigratedFromV1()) {
            // Persist immediately so the schema 2 file and its backup exist even if nothing else happens this session.
            this.save();
        }
    }

    private String describe(Set<UUID> uuids) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : uuids) {
            names.add(this.graph.get(uuid).map(SponsorEntry::displayName).orElse(uuid.toString()));
        }
        return String.join(", ", names);
    }

    /**
     * Holds the whitelist OFF until someone claims the root, and says so loudly.
     *
     * <p>{@code forceWhitelistOn} is deliberately ignored while in this state: the two settings would otherwise fight,
     * and the whole point of bootstrap is that the gate cannot be closed before there is anybody behind it.
     */
    private void enterBootstrap() {
        this.whitelist.setEnforced(false);

        String restriction = BootstrapPolicy.describeRestriction(Config.BOOTSTRAP_RESTRICT_TO_NAME.get());
        boolean loopbackOnly = Config.BOOTSTRAP_RESTRICT_TO_LOOPBACK.get();
        Sponsorsystem.LOGGER.warn("========================================================================");
        Sponsorsystem.LOGGER.warn("  SPONSORSHIP: THIS SERVER IS UNPROTECTED AND WAITING FOR ITS FOUNDER.");
        Sponsorsystem.LOGGER.warn("  The support graph is empty, so there is nobody to whitelist yet.");
        Sponsorsystem.LOGGER.warn("  The whitelist has been turned OFF on purpose so the first player can get in.");
        Sponsorsystem.LOGGER.warn("  May claim the root: {}{}", restriction, loopbackOnly ? " (local connections only)" : "");
        Sponsorsystem.LOGGER.warn("  The whitelist engages AUTOMATICALLY the moment they join, and this state");
        Sponsorsystem.LOGGER.warn("  can never be re-entered while sponsors.json has any entry in it.");
        Sponsorsystem.LOGGER.warn("  To close the window now: stop the server, set bootstrap.restrictToName in");
        Sponsorsystem.LOGGER.warn("  config/sponsorsystem-server.toml, and start it again.");
        Sponsorsystem.LOGGER.warn("========================================================================");
    }

    private void applyWhitelistPolicy() {
        if (Config.FORCE_WHITELIST_ON.get() && !this.whitelist.isEnforced()) {
            this.whitelist.setEnforced(true);
            Sponsorsystem.LOGGER.warn("The whitelist was OFF and forceWhitelistOn is true, so it has been turned ON. "
                    + "Anyone not in the support graph can no longer join.");
        } else if (!this.whitelist.isEnforced()) {
            Sponsorsystem.LOGGER.warn("The whitelist is OFF and forceWhitelistOn is false. Support will be recorded "
                    + "but nothing is actually gated - anyone can join.");
        }
    }

    /**
     * Warns about an empty graph, saying which of the two ways in applies.
     *
     * <p>Split out from {@link #applyWhitelistPolicy()} because the honest message depends on facts that method does
     * not have: with a populated whitelist the existing players can still get in, so "nobody can join" would be wrong.
     */
    private void warnEmptyGraph(boolean whitelistEmpty) {
        if (!this.graph.isEmpty()) {
            return;
        }
        if (!whitelistEmpty) {
            Sponsorsystem.LOGGER.warn("The support graph is empty, but whitelist.json already has entries, so this "
                    + "is not a brand new server and the founder bootstrap has NOT been started. The whitelist stays "
                    + "as it is, and the players on it can still join. Bring them into the graph with "
                    + "'/sponsorship adopt <player> [sponsor]'. If you really do want a fresh bootstrap, stop the "
                    + "server and delete whitelist.json as well.");
        } else if (!Config.BOOTSTRAP_ENABLED.get()) {
            Sponsorsystem.LOGGER.warn("The support graph is empty and bootstrap.enabled is false, so NOBODY can "
                    + "join this server. To recover: run '/op <name>' in this console (operators bypass the "
                    + "whitelist), join, then run '/sponsorship adopt <name>' to make yourself the root.");
        }
    }

    /**
     * Re-applies the graph to the whitelist and logs anything that was out of step.
     *
     * <p>Abandoned players keep their whitelist entry: losing support is recorded this round, not enforced.
     */
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
            Sponsorsystem.LOGGER.warn("Re-whitelisted {} supported player(s) that were missing from whitelist.json.", added);
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
            Sponsorsystem.LOGGER.warn("{} whitelisted player(s) are NOT part of the support graph. They stay "
                    + "whitelisted until an operator runs /sponsorship adopt <player> [sponsor]: {}",
                    orphans.size(), String.join(", ", orphans));
        }
    }

    /** Re-reads {@code sponsors.json} from disk, discarding the in-memory graph. @return warnings worth showing */
    public List<String> reload() throws IOException {
        List<String> warnings = new ArrayList<>();
        SponsorStore.Loaded loaded = this.store.load(warnings);
        warnings.addAll(this.graph.replaceAll(loaded.entries(), loaded.edges()));
        this.graph.recomputeSupport(this.supportModel(), this.fullGraceSeconds());
        this.reconcileWithWhitelist();
        return warnings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Persists the graph.
     *
     * @return {@code true} on success. On failure the in-memory graph is rolled back to whatever is on disk, so a
     *         caller that gets {@code false} can abort without leaving memory and disk disagreeing.
     */
    public boolean save() {
        try {
            this.store.save(this.graph.entries(), this.graph.allEdges());
            return true;
        } catch (IOException exception) {
            Sponsorsystem.LOGGER.error("Could not write {}; rolling the support graph back to the last saved state.",
                    this.store.getFile(), exception);
            try {
                List<String> warnings = new ArrayList<>();
                SponsorStore.Loaded loaded = this.store.load(warnings);
                this.graph.replaceAll(loaded.entries(), loaded.edges());
            } catch (IOException reloadFailure) {
                Sponsorsystem.LOGGER.error("Rollback failed as well; the in-memory graph may be ahead of {}.",
                        this.store.getFile(), reloadFailure);
            }
            return false;
        }
    }

    public void saveOnShutdown() {
        if (this.save()) {
            Sponsorsystem.LOGGER.info("Saved {} player(s) and {} support edge(s) to {}",
                    this.graph.size(), this.graph.edgeCount(), this.store.getFile());
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
            // Back onto the server thread before anything touches the graph, the whitelist, or a player's chat.
            this.server.execute(() -> callback.accept(lookup));
        });
    }

    /** Looks a name up in the graph. Names are display data, so this is a scan and ties are broken by liveness. */
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
    // Tickets
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether this player never runs out of tickets: the root, and anyone with enough permission. */
    public boolean hasUnlimitedTickets(UUID uuid) {
        if (this.graph.isRoot(uuid)) {
            return true;
        }
        ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
        return player != null && player.hasPermissions(Config.UNLIMITED_TICKETS_PERMISSION_LEVEL.get());
    }

    /**
     * The support budget for a player about to back someone.
     *
     * @param kind the edge they are about to write, used only when the budgets are split
     */
    public TicketBudget budgetFor(UUID supporter, EdgeKind kind) {
        boolean separate = Config.SEPARATE_INVITE_AND_SPONSOR_BUDGETS.get();
        return TicketBudget.of(this.graph, supporter, separate ? kind : null,
                Config.MAX_SUPPORT_TICKETS_PER_PLAYER.get(),
                Config.MAX_INVITES_PER_PLAYER.get(),
                Config.MAX_SPONSORSHIPS_PER_PLAYER.get(),
                this.hasUnlimitedTickets(supporter));
    }

    /** How long a sponsorship must exist before its author may take it back, in milliseconds. */
    public long sponsorshipMinDurationMillis() {
        return Config.SPONSORSHIP_MIN_DURATION_MINUTES.get() * 60_000L;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Applies a graph change that has already been made in memory: recompute support, persist, and tell everyone
     * affected.
     *
     * <p>Recompute happens before the save so the new statuses are what gets written. If the save fails, the graph is
     * rolled back to disk and nothing is announced.
     *
     * @param restoredByName who to credit for any restored support, or {@code null}
     * @return the support changes, or empty if the save failed and nothing happened
     */
    private Optional<SupportGraph.SupportChange> commit(@Nullable String restoredByName) {
        SupportGraph.SupportChange change = this.graph.recomputeSupport(this.supportModel(), this.fullGraceSeconds());
        if (!this.save()) {
            return Optional.empty();
        }
        this.announceSupportChange(change, restoredByName);
        return Optional.of(change);
    }

    private void announceSupportChange(SupportGraph.SupportChange change, @Nullable String restoredByName) {
        for (UUID uuid : change.abandoned()) {
            SponsorEntry entry = this.graph.get(uuid).orElse(null);
            String name = entry == null ? uuid.toString() : entry.displayName();
            int remaining = entry == null ? 0 : entry.getGraceSecondsRemaining();
            Sponsorsystem.LOGGER.info("{} has lost all support and is now ABANDONED, with {} of grace remaining.",
                    name, GraceCountdown.format(remaining));
            // Offline players are told on their next login instead; the clock is not running for them yet anyway.
            this.tell(uuid, Messages.error("sponsorsystem.support.lost",
                    GraceCountdown.minutesRemaining(remaining)));
        }
        for (UUID uuid : change.restored()) {
            String name = this.graph.get(uuid).map(SponsorEntry::displayName).orElse(uuid.toString());
            Sponsorsystem.LOGGER.info("{} has support again; their grace clock has been cleared.", name);
            this.tell(uuid, restoredByName == null
                    ? Messages.success("sponsorsystem.support.restored")
                    : Messages.success("sponsorsystem.support.restored_by", Messages.name(restoredByName)));
            // The countdown is on the action bar, which nothing else will clear for us.
            this.clearActionBar(uuid);
        }
    }

    /** Wipes a stale countdown off the action bar by overwriting it with an empty component. */
    private void clearActionBar(UUID uuid) {
        ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            player.displayClientMessage(Component.empty(), true);
        }
    }

    /**
     * Records a brand new player, backed by {@code supporter}, and whitelists them.
     *
     * <p>Order matters: the graph is updated and saved first, and the whitelist is only touched once the save
     * succeeded. If the disk write fails the graph is rolled back and nothing is whitelisted, so the two never
     * disagree.
     */
    public Optional<SupportEdge> commitInvite(UUID supporter, GameProfile invitee, int spendable) {
        SupportEdge edge = this.graph.invite(supporter, invitee.getId(), invitee.getName(),
                System.currentTimeMillis(), spendable);
        if (this.commit(null).isEmpty()) {
            return Optional.empty();
        }
        this.whitelist.add(invitee);
        return Optional.of(edge);
    }

    /** Adds support for someone already here. Grants no access, so the whitelist is untouched. */
    public Optional<SupportEdge> commitSponsor(UUID supporter, UUID supported, String supporterName, int spendable) {
        SupportEdge edge = this.graph.sponsor(supporter, supported, System.currentTimeMillis(), spendable);
        return this.commit(supporterName).isEmpty() ? Optional.empty() : Optional.of(edge);
    }

    /**
     * Removes one supporter's backing of one player.
     *
     * <p>Nobody is unwhitelisted or kicked here. The recompute decides who has lost support, and this round that is
     * recorded rather than acted on.
     */
    public Optional<WithdrawResult> commitWithdraw(UUID supporter, UUID supported, boolean bypassAgeCheck) {
        SupportEdge removed = this.graph.withdraw(supporter, supported, System.currentTimeMillis(),
                this.sponsorshipMinDurationMillis(), bypassAgeCheck);
        // If the edge that brought them in is gone but someone else still backs them, keep a skeleton edge so the
        // tree still has a line to draw. This is bookkeeping only - the promoted supporter was already just as
        // answerable for this player as everyone else backing them.
        Optional<SupportEdge> promoted = removed.isPrimary()
                ? this.graph.promoteOldestSponsorship(supported)
                : Optional.empty();

        Optional<SupportGraph.SupportChange> change = this.commit(null);
        return change.map(value -> new WithdrawResult(removed, promoted.orElse(null),
                value.abandoned().contains(supported)));
    }

    /**
     * Recomputes support and saves, for a change already made directly on the graph.
     *
     * <p>Used by the operator commands, which edit edges themselves rather than going through invite or
     * sponsor, and still need the same recompute-then-persist step everything else gets.
     */
    public Optional<SupportGraph.SupportChange> commitRecompute() {
        return this.commit(null);
    }

    /** What one withdrawal did. */
    public record WithdrawResult(SupportEdge removed, @Nullable SupportEdge promoted, boolean abandonedTarget) {}

    /** Adds a player with no supporter, whitelisting them if they were not already. Used by adopt and the founder. */
    public Optional<SponsorEntry> commitAddRoot(GameProfile profile, SponsorStatus status) {
        SponsorEntry entry = this.graph.addEntry(profile.getId(), profile.getName(), System.currentTimeMillis(), status);
        this.graph.markRoot(profile.getId(), true);
        if (this.commit(null).isEmpty()) {
            return Optional.empty();
        }
        this.whitelist.add(profile);
        return Optional.of(entry);
    }

    /** Adds a player under an existing supporter, whitelisting them if they were not already. */
    public Optional<SponsorEntry> commitAdoptUnder(GameProfile profile, UUID supporter, SponsorStatus status) {
        long now = System.currentTimeMillis();
        SponsorEntry entry = this.graph.addEntry(profile.getId(), profile.getName(), now, status);
        this.graph.markRoot(profile.getId(), false);
        if (this.graph.edge(supporter, profile.getId()).isEmpty()) {
            this.graph.adoptEdge(supporter, profile.getId(), now);
        }
        if (this.commit(null).isEmpty()) {
            return Optional.empty();
        }
        this.whitelist.add(profile);
        return Optional.of(entry);
    }

    /**
     * Operator removal: drops every edge into the player, marks them REVOKED, unwhitelists and kicks them.
     *
     * <p>Everyone downstream is left in place; the recompute decides who has lost support as a result, and this round
     * that is recorded rather than acted on.
     */
    public Optional<SupportGraph.SupportChange> commitRevoke(UUID target, Component kickReason) {
        SponsorEntry entry = this.graph.get(target).orElse(null);
        this.graph.revoke(target);
        Optional<SupportGraph.SupportChange> change = this.commit(null);
        if (change.isEmpty()) {
            return change;
        }
        this.whitelist.remove(target, entry == null ? null : entry.getLastKnownName());
        this.kick(target, kickReason);
        return change;
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

    /** Sends a message to a player if they are online; silently does nothing otherwise. */
    public void tell(UUID uuid, Component message) {
        ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Player lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Handles a join: claims the founder slot while bootstrapping, otherwise flips a PENDING invite to ACTIVE on
     * first join and refreshes the cached name.
     */
    public void onPlayerLoggedIn(ServerPlayer player) {
        if (this.state == ServerState.BOOTSTRAP) {
            this.handleBootstrapLogin(player);
            return;
        }

        UUID uuid = player.getUUID();
        if (!this.graph.contains(uuid)) {
            return;
        }

        String name = player.getGameProfile().getName();
        boolean renamed = this.graph.refreshName(uuid, name);
        boolean activated = this.graph.activate(uuid, System.currentTimeMillis());
        if (activated) {
            Sponsorsystem.LOGGER.info("{} joined for the first time; their support is now active.", name);
        }
        if (renamed || activated) {
            this.save();
        }
        if (this.graph.get(uuid).map(entry -> entry.getStatus() == SponsorStatus.ABANDONED).orElse(false)) {
            // Their clock has been paused since they logged out. Say so on the way in, because the action bar
            // countdown is easy to miss and says nothing about why it is there.
            SponsorEntry entry = this.graph.get(uuid).orElseThrow();
            if (!entry.hasGraceClock()) {
                this.graph.startGrace(uuid, this.fullGraceSeconds());
                this.save();
            }
            player.sendSystemMessage(Messages.error("sponsorsystem.support.lost_on_join",
                    GraceCountdown.minutesRemaining(entry.getGraceSecondsRemaining())));
            player.sendSystemMessage(Messages.info("sponsorsystem.support.lost_on_join_detail"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Founder bootstrap
    // ---------------------------------------------------------------------------------------------------------------

    private void handleBootstrapLogin(ServerPlayer player) {
        GameProfile profile = player.getGameProfile();
        BootstrapDecision decision = BootstrapPolicy.evaluateClaim(
                this.state,
                Config.BOOTSTRAP_ENABLED.get(),
                profile.getName(),
                Config.BOOTSTRAP_RESTRICT_TO_NAME.get(),
                Config.BOOTSTRAP_RESTRICT_TO_LOOPBACK.get(),
                isLoopback(player));

        if (!decision.isClaim()) {
            Sponsorsystem.LOGGER.warn("{} ({}) could not claim the founder slot: {}. Disconnecting them; the server "
                    + "stays in BOOTSTRAP.", profile.getName(), profile.getId(), decision);
            player.connection.disconnect(Component.translatable("sponsorsystem.bootstrap.rejected"));
            return;
        }
        this.claimFounder(player);
    }

    /**
     * Makes this player the root of the graph and closes the bootstrap window.
     *
     * <p>The disk write happens before the whitelist and the state flag are touched. If persisting fails, a server
     * that had already flipped to ESTABLISHED with the whitelist on would lock everyone out, including the founder it
     * just failed to record; writing first means a failure leaves the server exactly as it was.
     */
    private void claimFounder(ServerPlayer player) {
        GameProfile profile = player.getGameProfile();

        if (this.commitAddRoot(profile, SponsorStatus.ACTIVE).isEmpty()) {
            Sponsorsystem.LOGGER.error("Could not write {} while {} was claiming the root. The claim has been undone "
                    + "and the server stays in BOOTSTRAP.", this.store.getFile(), profile.getName());
            player.sendSystemMessage(Messages.error("sponsorsystem.bootstrap.save_failed"));
            return;
        }

        this.state = ServerState.ESTABLISHED;
        this.whitelist.setEnforced(true);

        if (Config.BOOTSTRAP_OP_FOUNDER.get()) {
            this.server.getPlayerList().op(profile);
            Sponsorsystem.LOGGER.info("Granted operator status to founder {} (bootstrap.opFounder is true).",
                    profile.getName());
        }

        int kicked = this.kickUnlistedPlayers();
        Sponsorsystem.LOGGER.info("FOUNDER CLAIMED: {} ({}) is now the root of the support graph. The whitelist is "
                + "ON and this server is no longer open.", profile.getName(), profile.getId());
        if (kicked > 0) {
            Sponsorsystem.LOGGER.warn("Removed {} player(s) who joined during the bootstrap window and are not "
                    + "whitelisted.", kicked);
        }

        player.sendSystemMessage(Messages.success("sponsorsystem.bootstrap.claimed"));
        player.sendSystemMessage(Messages.info("sponsorsystem.bootstrap.claimed_detail"));
    }

    /**
     * Disconnects everyone online who is not on the whitelist, and reports how many.
     *
     * <p>{@link MinecraftServer#kickUnlistedPlayers(net.minecraft.commands.CommandSourceStack)} does exactly this, but
     * it is gated on {@code isEnforceWhitelist()} — the {@code enforce-whitelist} server property, which is false on a
     * default server — so on its own it would silently do nothing here. It is still called for parity with vanilla
     * behaviour, and then the same sweep is done unconditionally so the guarantee actually holds.
     */
    private int kickUnlistedPlayers() {
        this.server.kickUnlistedPlayers(this.server.createCommandSourceStack());

        int kicked = 0;
        // Copy first: disconnecting mutates the live player list.
        for (ServerPlayer online : List.copyOf(this.server.getPlayerList().getPlayers())) {
            if (!this.whitelist.isWhitelisted(online.getGameProfile())) {
                online.connection.disconnect(Component.translatable("multiplayer.disconnect.not_whitelisted"));
                kicked++;
            }
        }
        return kicked;
    }

    /**
     * Whether this player connected from the local machine.
     *
     * <p>A single-player or LAN-host connection is in-process rather than over a socket, so
     * {@link Connection#isMemoryConnection()} is checked first; it is local by definition.
     */
    private static boolean isLoopback(ServerPlayer player) {
        Connection connection = player.connection.getConnection();
        if (connection.isMemoryConnection()) {
            return true;
        }
        SocketAddress address = connection.getRemoteAddress();
        return address instanceof InetSocketAddress inet
                && inet.getAddress() != null
                && inet.getAddress().isLoopbackAddress();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Expiry
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Called every server tick.
     *
     * <p>Both jobs here are gated behind their own counters, and the grace pass returns immediately when nobody is
     * abandoned, so on a healthy server this costs one decrement and one comparison per tick and nothing else. It
     * never walks the player list; it walks the abandoned set, which is empty in the normal case.
     */
    public void tick() {
        if (Config.PENDING_INVITE_EXPIRY_HOURS.get() > 0 && --this.ticksUntilExpirySweep <= 0) {
            this.ticksUntilExpirySweep = TICKS_PER_HOUR;
            this.sweepExpiredInvites();
        }

        if (--this.ticksUntilGraceTick > 0) {
            return;
        }
        this.ticksUntilGraceTick = TICKS_PER_GRACE_TICK;
        this.tickGrace();
    }

    /**
     * Burns one second off every online abandoned player's clock, updates their countdown, and expires anyone who
     * has run out.
     *
     * <p>Called once a second, not once a tick. Offline players are skipped entirely, which is what makes the clock
     * count playtime rather than wall-clock time — there is no elapsed-time arithmetic anywhere, just "you were here
     * for another second".
     */
    private void tickGrace() {
        List<UUID> abandoned = this.graph.abandonedPlayers();
        if (abandoned.isEmpty()) {
            return;
        }

        boolean showCountdown = Config.ABANDONED_COUNTDOWN_ENABLED.get();
        List<UUID> expired = new ArrayList<>();
        boolean changed = false;

        for (UUID uuid : abandoned) {
            ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                continue; // Offline: the clock is paused, not running.
            }
            SponsorEntry entry = this.graph.get(uuid).orElse(null);
            if (entry == null) {
                continue;
            }
            if (!entry.hasGraceClock()) {
                // Abandoned before this feature existed, or loaded from an older file: give them a full window.
                this.graph.startGrace(uuid, this.fullGraceSeconds());
            }

            int before = entry.getGraceSecondsRemaining();
            int remaining = this.graph.decrementGrace(uuid, TICK_SECONDS);
            changed = true;

            int warning = GraceCountdown.warningCrossed(before, remaining);
            if (warning > 0) {
                player.sendSystemMessage(Messages.graceWarning(warning));
            }
            if (remaining <= 0) {
                expired.add(uuid);
            } else if (showCountdown) {
                // One component, once a second, only for players who are both abandoned and online.
                player.displayClientMessage(Messages.graceActionBar(remaining), true);
            }
        }

        for (UUID uuid : expired) {
            this.expirePlayer(uuid);
        }
        if (changed && expired.isEmpty()) {
            // Expiry saves on its own; this covers the ordinary case of clocks simply ticking down.
            this.save();
        }
    }

    /** How long a fresh grace period is, in seconds. */
    public int fullGraceSeconds() {
        return GraceCountdown.minutesToSeconds(Config.ABANDONED_GRACE_MINUTES.get());
    }

    /**
     * Removes a player whose grace ran out, then lets the support model deal with the consequences.
     *
     * <p>There is deliberately no cascade code here. Expiring marks them not-live, and the recompute inside
     * {@link #commit} refuses to carry support through anyone who is not live — so everyone who depended on them is
     * abandoned automatically, each starting a full grace period of their own.
     */
    private void expirePlayer(UUID uuid) {
        SponsorEntry entry = this.graph.get(uuid).orElse(null);
        String name = entry == null ? uuid.toString() : entry.displayName();
        this.graph.expire(uuid);

        if (this.commit(null).isEmpty()) {
            Sponsorsystem.LOGGER.error("Could not persist the expiry of {}; it has been rolled back and will be "
                    + "retried.", name);
            return;
        }

        this.whitelist.remove(uuid, entry == null ? null : entry.getLastKnownName());
        this.kick(uuid, Component.translatable("sponsorsystem.kick.support_expired"));
        Sponsorsystem.LOGGER.info("{} ran out of grace with nobody backing them and has been removed from the "
                + "whitelist.", name);
        SponsorCommands.announce(this, Messages.info("sponsorsystem.support.expired_announce", Messages.name(name)));
    }

    /** Revokes and unwhitelists invites that were never taken up within the configured window. */
    public void sweepExpiredInvites() {
        int hours = Config.PENDING_INVITE_EXPIRY_HOURS.get();
        if (hours <= 0) {
            return;
        }

        long cutoff = System.currentTimeMillis() - (long) hours * 60L * 60L * 1000L;
        // Collect UUIDs rather than entries: a failed save rebuilds the graph from disk, which would leave any entry
        // objects captured here pointing at a graph that no longer exists.
        List<UUID> stale = this.graph.pendingInvitedBefore(cutoff).stream().map(SponsorEntry::getUuid).toList();
        if (stale.isEmpty()) {
            return;
        }

        Component reason = Component.translatable("sponsorsystem.kick.expired");
        for (UUID uuid : stale) {
            SponsorEntry entry = this.graph.get(uuid).orElse(null);
            if (entry == null || entry.getStatus() != SponsorStatus.PENDING) {
                continue;
            }
            String name = entry.displayName();
            if (this.commitRevoke(uuid, reason).isEmpty()) {
                Sponsorsystem.LOGGER.error("Could not expire the invite for {}; abandoning this sweep.", name);
                return;
            }
            Sponsorsystem.LOGGER.info("Invite for {} expired after {} hours and was withdrawn.", name, hours);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers shared by the commands
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether this profile is an operator. Operators bypass the whitelist and several of the limits. */
    public boolean isOperator(GameProfile profile) {
        return this.server.getPlayerList().isOp(profile);
    }

    /** Every name worth offering for tab completion: everyone in the graph, plus everyone on the whitelist. */
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

    /** Names of the players this player currently backs — the only people they may withdraw from. */
    public List<String> supportedNames(UUID supporter) {
        List<String> names = new ArrayList<>();
        for (UUID target : this.graph.supportedBy(supporter)) {
            this.graph.get(target).map(SponsorEntry::displayName).ifPresent(names::add);
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
