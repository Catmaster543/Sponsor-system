package com.fiskerz.sponsor_system.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fiskerz.sponsor_system.graph.SponsorGraphException.Reason;

/**
 * Who supports whom: players, and the directed edges between them.
 *
 * <p>This is a <strong>DAG only by convention, not by construction</strong> — a player may legally sponsor their own
 * ancestor, which is a genuine vote of confidence and creates a cycle in the edge set. Nothing here rejects cycles.
 * Every traversal therefore carries a visited set; there is no depth at which a cycle stops being possible.
 *
 * <p>Every supporter of a player is equally answerable for them. {@link EdgeKind} records how a relationship started
 * and gives the tree renderer a skeleton, and means nothing beyond that.
 *
 * <p>Deliberately contains <strong>no Minecraft imports</strong>, so all of it is unit testable without a game. Not
 * thread safe: every caller runs on the server thread.
 */
public final class SupportGraph {
    /** Insertion-ordered so a hand-read {@code sponsors.json} keeps a stable, diffable layout. */
    private final Map<UUID, SponsorEntry> entries = new LinkedHashMap<>();
    /** Derived index: supporter -> their outgoing edges, in creation order. */
    private final Map<UUID, List<SupportEdge>> outgoing = new HashMap<>();
    /** Derived index: supported -> their incoming edges, in creation order. */
    private final Map<UUID, List<SupportEdge>> incoming = new HashMap<>();

    // -------------------------------------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------------------------------------

    public int size() {
        return this.entries.size();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public Collection<SponsorEntry> entries() {
        return Collections.unmodifiableCollection(this.entries.values());
    }

    public Optional<SponsorEntry> get(UUID uuid) {
        return Optional.ofNullable(this.entries.get(uuid));
    }

    public boolean contains(UUID uuid) {
        return this.entries.containsKey(uuid);
    }

    /** Every edge in the graph, in no particular order. */
    public List<SupportEdge> allEdges() {
        List<SupportEdge> all = new ArrayList<>();
        this.outgoing.values().forEach(all::addAll);
        return all;
    }

    public int edgeCount() {
        int count = 0;
        for (List<SupportEdge> list : this.outgoing.values()) {
            count += list.size();
        }
        return count;
    }

    /** The edges written by this player: everyone they back. */
    public List<SupportEdge> edgesFrom(UUID supporter) {
        return List.copyOf(this.outgoing.getOrDefault(supporter, List.of()));
    }

    /** The edges pointing at this player: everyone backing them. */
    public List<SupportEdge> edgesTo(UUID supported) {
        return List.copyOf(this.incoming.getOrDefault(supported, List.of()));
    }

    public Optional<SupportEdge> edge(UUID from, UUID to) {
        return this.outgoing.getOrDefault(from, List.of()).stream()
                .filter(candidate -> candidate.to().equals(to))
                .findFirst();
    }

    /** The one edge that brought this player onto the server, if it still exists. */
    public Optional<SupportEdge> primaryEdgeTo(UUID supported) {
        return this.edgesTo(supported).stream().filter(SupportEdge::isPrimary).findFirst();
    }

    /** The player who invited this one, if any. */
    public Optional<UUID> inviterOf(UUID supported) {
        return this.primaryEdgeTo(supported).map(SupportEdge::from);
    }

    /** Everyone backing this player, inviter and sponsors alike, in the order the edges were written. */
    public List<UUID> supportersOf(UUID supported) {
        return this.edgesTo(supported).stream().map(SupportEdge::from).toList();
    }

    /** Everyone this player backs. */
    public List<UUID> supportedBy(UUID supporter) {
        return this.edgesFrom(supporter).stream().map(SupportEdge::to).toList();
    }

    /** The declared anchors of the graph. These are the seeds the reachability model spreads out from. */
    public List<SponsorEntry> roots() {
        List<SponsorEntry> roots = new ArrayList<>();
        for (SponsorEntry entry : this.entries.values()) {
            if (entry.isRoot()) {
                roots.add(entry);
            }
        }
        return roots;
    }


    public boolean isRoot(UUID uuid) {
        return this.get(uuid).map(SponsorEntry::isRoot).orElse(false);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------------------------------------------

    /**
     * Replaces the whole graph, dropping anything inconsistent rather than refusing to load — an admin who hand-edits
     * {@code sponsors.json} into a bad state should still get a running server.
     *
     * <p>Cycles are <em>not</em> a defect here and are preserved as written.
     *
     * @return human-readable warnings describing every repair made, for logging at startup
     */
    public List<String> replaceAll(Collection<SponsorEntry> loadedEntries, Collection<SupportEdge> loadedEdges) {
        this.entries.clear();
        this.outgoing.clear();
        this.incoming.clear();
        List<String> warnings = new ArrayList<>();

        for (SponsorEntry entry : loadedEntries) {
            if (this.entries.put(entry.getUuid(), entry) != null) {
                warnings.add("Duplicate entry for " + entry.getUuid() + "; kept the last one in the file.");
            }
        }

        for (SupportEdge edge : loadedEdges) {
            if (edge.from().equals(edge.to())) {
                warnings.add("Dropped a self-supporting edge for " + this.describe(edge.from()) + ".");
                continue;
            }
            if (!this.entries.containsKey(edge.from())) {
                warnings.add("Dropped an edge from unknown player " + edge.from() + ".");
                continue;
            }
            if (!this.entries.containsKey(edge.to())) {
                warnings.add("Dropped an edge to unknown player " + edge.to() + ".");
                continue;
            }
            if (this.edge(edge.from(), edge.to()).isPresent()) {
                warnings.add("Dropped a duplicate edge " + this.describe(edge.from()) + " -> " + this.describe(edge.to()) + ".");
                continue;
            }
            if (edge.isPrimary() && this.primaryEdgeTo(edge.to()).isPresent()) {
                // Only one edge may be the one that brought a player in; keep the first and demote the rest.
                warnings.add(this.describe(edge.to()) + " had more than one primary supporter in the file; "
                        + this.describe(edge.from()) + " was recorded as a sponsor instead.");
                this.index(edge.withKind(EdgeKind.SPONSORSHIP));
                continue;
            }
            this.index(edge);
        }
        return warnings;
    }

    private String describe(UUID uuid) {
        SponsorEntry entry = this.entries.get(uuid);
        return entry == null ? uuid.toString() : entry.displayName();
    }

    private void index(SupportEdge edge) {
        this.outgoing.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge);
        this.incoming.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edge);
    }

    private void unindex(SupportEdge edge) {
        List<SupportEdge> from = this.outgoing.get(edge.from());
        if (from != null) {
            from.removeIf(candidate -> candidate.to().equals(edge.to()));
        }
        List<SupportEdge> to = this.incoming.get(edge.to());
        if (to != null) {
            to.removeIf(candidate -> candidate.from().equals(edge.from()));
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------------------------------------------

    /**
     * Brings a brand new player onto the server, backed by {@code supporter}.
     *
     * @param maxTickets the supporter's remaining allowance check, or a negative number for unlimited
     * @throws SponsorGraphException if the invite is refused
     */
    public SupportEdge invite(UUID supporter, UUID invitee, String inviteeName, long now, int spendable) {
        this.checkSupporter(supporter, invitee);
        if (this.entries.containsKey(invitee)) {
            throw new SponsorGraphException(Reason.ALREADY_IN_GRAPH, invitee);
        }
        if (spendable == 0) {
            throw new SponsorGraphException(Reason.NO_TICKETS_LEFT, supporter);
        }

        this.entries.put(invitee, new SponsorEntry(invitee, inviteeName, now, 0L, SponsorStatus.PENDING));
        SupportEdge edge = new SupportEdge(supporter, invitee, EdgeKind.PRIMARY, now);
        this.index(edge);
        return edge;
    }

    /**
     * Adds support for a player who is already here.
     *
     * <p>Sponsoring your own inviter, or anyone else above you, is legal and creates a cycle. That is the point: it is
     * a real statement of confidence, and every traversal is built to cope with it.
     *
     * @param spendable the supporter's remaining allowance, or a negative number for unlimited
     * @throws SponsorGraphException if the sponsorship is refused
     */
    public SupportEdge sponsor(UUID supporter, UUID supported, long now, int spendable) {
        this.checkSupporter(supporter, supported);
        if (!this.entries.containsKey(supported)) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, supported);
        }
        if (this.edge(supporter, supported).isPresent()) {
            throw new SponsorGraphException(Reason.DUPLICATE_EDGE, supported);
        }
        if (spendable == 0) {
            throw new SponsorGraphException(Reason.NO_TICKETS_LEFT, supporter);
        }

        SupportEdge edge = new SupportEdge(supporter, supported, EdgeKind.SPONSORSHIP, now);
        this.index(edge);
        return edge;
    }

    private void checkSupporter(UUID supporter, UUID supported) {
        if (supporter.equals(supported)) {
            throw new SponsorGraphException(Reason.SELF_SUPPORT);
        }
        SponsorEntry entry = this.entries.get(supporter);
        if (entry == null) {
            throw new SponsorGraphException(Reason.SUPPORTER_NOT_IN_GRAPH, supporter);
        }
        if (entry.getStatus() == SponsorStatus.REVOKED) {
            throw new SponsorGraphException(Reason.SUPPORTER_REVOKED, supporter);
        }
    }

    /**
     * Removes one supporter's backing of one player, whatever kind of edge it was.
     *
     * <p>Withdrawal is a single operation precisely because every supporter carries the same weight: there is no
     * meaningful difference between taking back an invite and taking back a sponsorship.
     *
     * @param minSponsorshipAgeMillis how long a sponsorship must have existed before it can be taken back; ignored for
     *                                primary edges and when {@code bypassAgeCheck} is set
     * @throws SponsorGraphException if there is no such edge, or it is too young to withdraw
     */
    public SupportEdge withdraw(UUID supporter, UUID supported, long now, long minSponsorshipAgeMillis,
            boolean bypassAgeCheck) {
        SupportEdge edge = this.edge(supporter, supported)
                .orElseThrow(() -> new SponsorGraphException(Reason.NO_SUCH_EDGE, supported));

        if (!bypassAgeCheck && !edge.isPrimary() && minSponsorshipAgeMillis > 0
                && edge.ageMillis(now) < minSponsorshipAgeMillis) {
            throw new SponsorGraphException(Reason.SPONSORSHIP_TOO_YOUNG, supported);
        }

        this.unindex(edge);
        return edge;
    }

    /**
     * Promotes this player's oldest remaining sponsorship to primary, so the tree still has a skeleton edge to draw
     * them from after their inviter withdrew.
     *
     * <p>Purely bookkeeping. The promoted supporter gains nothing and takes on nothing: they were already fully
     * answerable for this player, as every supporter is.
     *
     * @return the promoted edge, or empty if there was nothing to promote or a primary edge still exists
     */
    public Optional<SupportEdge> promoteOldestSponsorship(UUID supported) {
        if (this.primaryEdgeTo(supported).isPresent()) {
            return Optional.empty();
        }
        Optional<SupportEdge> oldest = this.edgesTo(supported).stream()
                .min(Comparator.comparingLong(SupportEdge::createdAt));
        oldest.ifPresent(edge -> {
            this.unindex(edge);
            this.index(edge.withKind(EdgeKind.PRIMARY));
        });
        return oldest.map(edge -> edge.withKind(EdgeKind.PRIMARY));
    }

    /**
     * Adds a player directly, with no supporter. Used by {@code /sponsorship adopt} and by the founder claim.
     *
     * @return the entry, newly created or already present
     */
    public SponsorEntry addEntry(UUID uuid, String name, long now, SponsorStatus status) {
        SponsorEntry existing = this.entries.get(uuid);
        if (existing != null) {
            existing.setStatus(status);
            if (name != null) {
                existing.setLastKnownName(name);
            }
            return existing;
        }
        SponsorEntry entry = new SponsorEntry(uuid, name, now, status == SponsorStatus.ACTIVE ? now : 0L, status);
        this.entries.put(uuid, entry);
        return entry;
    }


    /** Declares (or un-declares) a player as an anchor of the graph. Used by adopt and the founder claim. */
    public void markRoot(UUID uuid, boolean root) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry != null) {
            entry.setRoot(root);
        }
    }

    /**
     * Writes a supporting edge on an operator's say-so, skipping the checks {@link #sponsor} applies.
     *
     * <p>Used by {@code /sponsorship adopt} and {@code /sponsorship reassign}, where the operator is stating a fact
     * about the graph rather than spending their own tickets. It becomes the target's primary edge if they do not
     * already have one, so the tree has a line to draw them from.
     */
    public SupportEdge adoptEdge(UUID supporter, UUID supported, long now) {
        if (supporter.equals(supported)) {
            throw new SponsorGraphException(Reason.SELF_SUPPORT);
        }
        if (!this.entries.containsKey(supporter)) {
            throw new SponsorGraphException(Reason.SUPPORTER_NOT_IN_GRAPH, supporter);
        }
        if (!this.entries.containsKey(supported)) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, supported);
        }
        Optional<SupportEdge> existing = this.edge(supporter, supported);
        if (existing.isPresent()) {
            throw new SponsorGraphException(Reason.DUPLICATE_EDGE, supported);
        }
        EdgeKind kind = this.primaryEdgeTo(supported).isPresent() ? EdgeKind.SPONSORSHIP : EdgeKind.PRIMARY;
        SupportEdge edge = new SupportEdge(supporter, supported, kind, now);
        this.index(edge);
        return edge;
    }

    /**
     * Moves a player's primary edge to a new supporter, for {@code /sponsorship reassign}.
     *
     * @return the new primary edge
     */
    public SupportEdge reassignPrimary(UUID supported, UUID newSupporter, long now) {
        if (supported.equals(newSupporter)) {
            throw new SponsorGraphException(Reason.SELF_SUPPORT);
        }
        if (!this.entries.containsKey(supported)) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, supported);
        }
        if (!this.entries.containsKey(newSupporter)) {
            throw new SponsorGraphException(Reason.SUPPORTER_NOT_IN_GRAPH, newSupporter);
        }
        this.primaryEdgeTo(supported).ifPresent(this::unindex);
        // If the new supporter already backs them as a sponsor, promote that edge rather than writing a second one.
        this.edge(newSupporter, supported).ifPresent(existing -> {
            this.unindex(existing);
            this.index(existing.withKind(EdgeKind.PRIMARY));
        });
        if (this.edge(newSupporter, supported).isEmpty()) {
            this.index(new SupportEdge(newSupporter, supported, EdgeKind.PRIMARY, now));
        }
        this.markRoot(supported, false);
        return this.edge(newSupporter, supported).orElseThrow();
    }

    /** Marks a player as removed by an operator and deletes every edge into them. Their own edges out are kept. */
    public void revoke(UUID uuid) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry == null) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, uuid);
        }
        for (SupportEdge edge : this.edgesTo(uuid)) {
            this.unindex(edge);
        }
        entry.setStatus(SponsorStatus.REVOKED);
    }

    /** Deletes a player and every edge touching them in either direction. */
    public boolean forget(UUID uuid) {
        if (this.entries.remove(uuid) == null) {
            return false;
        }
        for (SupportEdge edge : this.edgesTo(uuid)) {
            this.unindex(edge);
        }
        for (SupportEdge edge : this.edgesFrom(uuid)) {
            this.unindex(edge);
        }
        this.outgoing.remove(uuid);
        this.incoming.remove(uuid);
        return true;
    }

    /** Refreshes the display name cached for a player. @return whether it actually changed */
    public boolean refreshName(UUID uuid, String name) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry == null || name == null || name.equals(entry.getLastKnownName())) {
            return false;
        }
        entry.setLastKnownName(name);
        return true;
    }

    /** Marks a pending invite as taken up on first join. @return whether this was the PENDING to ACTIVE transition */
    public boolean activate(UUID uuid, long now) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry == null || entry.getStatus() != SponsorStatus.PENDING) {
            return false;
        }
        entry.setStatus(SponsorStatus.ACTIVE);
        entry.setAcceptedAt(now);
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Support model
    // -------------------------------------------------------------------------------------------------------------

    /**
     * The set of players who currently have support.
     *
     * <p>Revoked players are never supported and never carry support onward, whichever model is in use — an operator
     * removed them, and that decision should not be undone by the graph.
     */
    public Set<UUID> computeSupported(SupportModel model) {
        return this.computeSupportedIgnoring(model, null, null);
    }

    /**
     * The supported set as it would be if one edge did not exist.
     *
     * <p>This is what lets {@code /uninvite} warn before it acts: the answer is produced by the same code the real
     * recompute uses, so the warning cannot disagree with what actually happens. Pass {@code null} for both ends to
     * ignore nothing.
     */
    public Set<UUID> computeSupportedIgnoring(SupportModel model, UUID ignoreFrom, UUID ignoreTo) {
        Set<UUID> supported = new LinkedHashSet<>();
        if (model == SupportModel.DIRECT) {
            for (SponsorEntry entry : this.entries.values()) {
                if (entry.getStatus() == SponsorStatus.REVOKED) {
                    continue;
                }
                boolean backed = this.edgesTo(entry.getUuid()).stream()
                        .anyMatch(edge -> !isIgnored(edge, ignoreFrom, ignoreTo));
                if (entry.isRoot() || backed) {
                    supported.add(entry.getUuid());
                }
            }
            return supported;
        }

        // REACHABILITY: spread outwards from the declared roots. The visited set is what makes a mutual-sponsorship
        // clique fail to hold itself up: nothing reaches it, so nothing ever enqueues it.
        Deque<UUID> queue = new ArrayDeque<>();
        for (SponsorEntry entry : this.entries.values()) {
            if (entry.getStatus() != SponsorStatus.REVOKED && entry.isRoot() && supported.add(entry.getUuid())) {
                queue.add(entry.getUuid());
            }
        }
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            for (SupportEdge edge : this.edgesFrom(current)) {
                if (isIgnored(edge, ignoreFrom, ignoreTo)) {
                    continue;
                }
                SponsorEntry target = this.entries.get(edge.to());
                if (target == null || target.getStatus() == SponsorStatus.REVOKED) {
                    continue;
                }
                if (supported.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return supported;
    }

    private static boolean isIgnored(SupportEdge edge, UUID ignoreFrom, UUID ignoreTo) {
        return ignoreFrom != null && ignoreTo != null
                && edge.from().equals(ignoreFrom) && edge.to().equals(ignoreTo);
    }

    /**
     * Recomputes support across the whole graph and moves statuses to match.
     *
     * <p>A full recompute every time is deliberate: the graph is hundreds of nodes at most, and removing a single edge
     * near a root can legitimately strand an entire branch at once. Incremental invalidation would be a bug farm for
     * no measurable gain.
     *
     * @return who newly lost support and who newly regained it
     */
    public SupportChange recomputeSupport(SupportModel model) {
        Set<UUID> supported = this.computeSupported(model);
        Set<UUID> abandoned = new LinkedHashSet<>();
        Set<UUID> restored = new LinkedHashSet<>();

        for (SponsorEntry entry : this.entries.values()) {
            if (entry.getStatus() == SponsorStatus.REVOKED) {
                continue;
            }
            boolean hasSupport = supported.contains(entry.getUuid());
            if (!hasSupport && entry.getStatus() != SponsorStatus.ABANDONED) {
                entry.setStatus(SponsorStatus.ABANDONED);
                abandoned.add(entry.getUuid());
            } else if (hasSupport && entry.getStatus() == SponsorStatus.ABANDONED) {
                // Back to whichever normal state they were in before: a player who never joined is still pending.
                entry.setStatus(entry.getAcceptedAt() > 0L ? SponsorStatus.ACTIVE : SponsorStatus.PENDING);
                restored.add(entry.getUuid());
            }
        }
        return new SupportChange(abandoned, restored);
    }

    /** What one recompute changed. */
    public record SupportChange(Set<UUID> abandoned, Set<UUID> restored) {
        public boolean isEmpty() {
            return this.abandoned.isEmpty() && this.restored.isEmpty();
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Tickets
    // -------------------------------------------------------------------------------------------------------------

    /**
     * How many tickets this player has spent, in total.
     *
     * <p>Edges pointing at a revoked player do not count. Someone who backed a player an operator later removed should
     * not go on paying for it.
     */
    public int spentTickets(UUID supporter) {
        return this.countSpent(supporter, null);
    }

    public int spentInvites(UUID supporter) {
        return this.countSpent(supporter, EdgeKind.PRIMARY);
    }

    public int spentSponsorships(UUID supporter) {
        return this.countSpent(supporter, EdgeKind.SPONSORSHIP);
    }

    private int countSpent(UUID supporter, EdgeKind kind) {
        int count = 0;
        for (SupportEdge edge : this.edgesFrom(supporter)) {
            if (kind != null && edge.kind() != kind) {
                continue;
            }
            SponsorEntry target = this.entries.get(edge.to());
            if (target != null && target.getStatus() != SponsorStatus.REVOKED) {
                count++;
            }
        }
        return count;
    }

    /** Epoch millis of this player's most recent invite, or {@code 0} if they have never invited anyone. */
    public long lastInviteAt(UUID supporter) {
        long latest = 0L;
        for (SupportEdge edge : this.edgesFrom(supporter)) {
            if (edge.isPrimary()) {
                latest = Math.max(latest, edge.createdAt());
            }
        }
        return latest;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Traversal
    // -------------------------------------------------------------------------------------------------------------

    /**
     * This player plus everyone reachable downstream of them, breadth first, the start first.
     *
     * <p>Carries a visited set: a sponsored ancestor makes cycles reachable from anywhere.
     */
    public List<UUID> descendantsOf(UUID start) {
        List<UUID> result = new ArrayList<>();
        if (!this.entries.containsKey(start)) {
            return result;
        }
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            result.add(current);
            for (SupportEdge edge : this.edgesFrom(current)) {
                if (seen.add(edge.to())) {
                    queue.addLast(edge.to());
                }
            }
        }
        return result;
    }

    /** Whether {@code candidate} is reachable downstream from {@code ancestor}. Nobody is their own descendant. */
    public boolean isDescendantOf(UUID candidate, UUID ancestor) {
        if (candidate == null || ancestor == null || candidate.equals(ancestor)) {
            return false;
        }
        return this.descendantsOf(ancestor).contains(candidate);
    }

    /**
     * The line of inviters above a player: the player first, then whoever invited them, and so on.
     *
     * <p>Follows PRIMARY edges only, because that is the skeleton {@code /invitetree} draws. Stops on a repeat, so a
     * sponsorship cycle promoted into the skeleton cannot loop forever.
     */
    public List<SponsorEntry> inviterChain(UUID uuid) {
        List<SponsorEntry> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = uuid;
        while (current != null && seen.add(current)) {
            SponsorEntry entry = this.entries.get(current);
            if (entry == null) {
                break;
            }
            chain.add(entry);
            current = this.inviterOf(current).orElse(null);
        }
        return chain;
    }

    /** Longest inviter chain in the graph, counted in players. */
    public int deepestChain() {
        int deepest = 0;
        for (SponsorEntry entry : this.entries.values()) {
            deepest = Math.max(deepest, this.inviterChain(entry.getUuid()).size());
        }
        return deepest;
    }

    /** Live entries that have been PENDING since before {@code cutoff}, i.e. invites that have gone stale. */
    public List<SponsorEntry> pendingInvitedBefore(long cutoff) {
        List<SponsorEntry> stale = new ArrayList<>();
        for (SponsorEntry entry : this.entries.values()) {
            if (entry.getStatus() == SponsorStatus.PENDING && entry.getInvitedAt() < cutoff) {
                stale.add(entry);
            }
        }
        return stale;
    }
}
