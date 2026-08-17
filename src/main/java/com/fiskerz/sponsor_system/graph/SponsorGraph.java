package com.fiskerz.sponsor_system.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 * The sponsorship tree: who invited whom.
 *
 * <p>Deliberately contains <strong>no Minecraft imports</strong> so it can be unit tested without a game instance.
 * Every mutation is validated here, and refusals are reported as {@link SponsorGraphException} with a machine-readable
 * reason; building player-facing text is the command layer's job.
 *
 * <p>This class is not thread safe. All access happens on the server thread — the only asynchronous part of the mod is
 * the Mojang profile lookup, which hops back onto the server thread before touching the graph.
 */
public final class SponsorGraph {
    /** Insertion-ordered so a hand-read {@code sponsors.json} keeps a stable, diffable layout. */
    private final Map<UUID, SponsorEntry> entries = new LinkedHashMap<>();
    /** Derived index: sponsor -> direct invitees. Includes revoked invitees so history stays inspectable. */
    private final Map<UUID, Set<UUID>> children = new HashMap<>();

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

    /**
     * Replaces the whole graph with the given entries, repairing anything inconsistent rather than refusing to load —
     * an admin who hand-edits {@code sponsors.json} into a bad state should still get a running server.
     *
     * @return human-readable warnings describing every repair made, for logging at startup
     */
    public List<String> replaceAll(Collection<SponsorEntry> loaded) {
        this.entries.clear();
        this.children.clear();
        List<String> warnings = new ArrayList<>();

        for (SponsorEntry entry : loaded) {
            SponsorEntry previous = this.entries.put(entry.getUuid(), entry);
            if (previous != null) {
                warnings.add("Duplicate entry for " + entry.getUuid() + "; kept the last one in the file.");
            }
        }

        // Drop sponsor pointers that go nowhere, then break any cycles, before building the child index.
        for (SponsorEntry entry : this.entries.values()) {
            UUID sponsor = entry.getSponsor();
            if (sponsor == null) {
                continue;
            }
            if (sponsor.equals(entry.getUuid())) {
                entry.setSponsor(null);
                warnings.add(entry.displayName() + " sponsored themselves in the file; promoted to root.");
            } else if (!this.entries.containsKey(sponsor)) {
                entry.setSponsor(null);
                warnings.add(entry.displayName() + " pointed at unknown sponsor " + sponsor + "; promoted to root.");
            }
        }

        for (SponsorEntry entry : this.entries.values()) {
            UUID cycleMember = this.findCycleFrom(entry.getUuid());
            if (cycleMember != null) {
                SponsorEntry broken = this.entries.get(cycleMember);
                broken.setSponsor(null);
                warnings.add("Sponsor cycle in the file involving " + broken.displayName() + "; promoted them to root.");
            }
        }

        for (SponsorEntry entry : this.entries.values()) {
            if (entry.getSponsor() != null) {
                this.childrenOf(entry.getSponsor()).add(entry.getUuid());
            }
        }
        return warnings;
    }

    /**
     * Walks up from {@code start}; if the walk revisits a node, returns one member of that cycle, else {@code null}.
     */
    private UUID findCycleFrom(UUID start) {
        Set<UUID> seen = new HashSet<>();
        UUID current = start;
        while (current != null && seen.add(current)) {
            SponsorEntry entry = this.entries.get(current);
            current = entry == null ? null : entry.getSponsor();
        }
        // Null means the walk reached a root; anything else is the node the walk came back around to.
        return current;
    }

    private Set<UUID> childrenOf(UUID sponsor) {
        return this.children.computeIfAbsent(sponsor, key -> new LinkedHashSet<>());
    }

    /**
     * Records {@code invitee} as sponsored by {@code sponsor}.
     *
     * <p>A previously {@link SponsorStatus#REVOKED} player may be invited again — their entry is reused so the UUID
     * keeps a single row in the file — but anyone with a live sponsorship is refused.
     *
     * @param maxInvites the sponsor's allowance, or a negative number for unlimited
     * @throws SponsorGraphException if the invite is refused
     */
    public SponsorEntry invite(UUID sponsor, UUID invitee, String inviteeName, long now, int maxInvites) {
        if (sponsor.equals(invitee)) {
            throw new SponsorGraphException(Reason.SELF_INVITE);
        }
        SponsorEntry sponsorEntry = this.entries.get(sponsor);
        if (sponsorEntry == null) {
            throw new SponsorGraphException(Reason.SPONSOR_NOT_IN_GRAPH, sponsor);
        }
        // A revoked player can still be online if they are an operator, since operators bypass the whitelist. They
        // must not be able to keep vouching for people after their own sponsorship was withdrawn.
        if (!sponsorEntry.getStatus().isLive()) {
            throw new SponsorGraphException(Reason.SPONSOR_REVOKED, sponsor);
        }

        SponsorEntry existing = this.entries.get(invitee);
        if (existing != null && existing.getStatus().isLive()) {
            throw new SponsorGraphException(Reason.ALREADY_SPONSORED, existing.getSponsor());
        }
        // Only possible when re-inviting a revoked player who still has the new sponsor somewhere beneath them.
        if (existing != null && this.isDescendantOf(sponsor, invitee)) {
            throw new SponsorGraphException(Reason.WOULD_CREATE_CYCLE, invitee);
        }
        if (maxInvites >= 0 && this.liveInviteCount(sponsor) >= maxInvites) {
            throw new SponsorGraphException(Reason.INVITE_LIMIT_REACHED, sponsor);
        }

        if (existing != null) {
            if (existing.getSponsor() != null) {
                this.childrenOf(existing.getSponsor()).remove(invitee);
            }
            existing.setSponsor(sponsor);
            existing.setInvitedAt(now);
            existing.setAcceptedAt(0L);
            existing.setStatus(SponsorStatus.PENDING);
            existing.setLastKnownName(inviteeName);
            this.childrenOf(sponsor).add(invitee);
            return existing;
        }

        SponsorEntry entry = new SponsorEntry(invitee, inviteeName, sponsor, now, 0L, SponsorStatus.PENDING);
        this.entries.put(invitee, entry);
        this.childrenOf(sponsor).add(invitee);
        return entry;
    }

    /**
     * Brings a player into the tree without the checks {@link #invite} applies — used by {@code /sponsorship adopt} for
     * whitelist entries that predate the mod.
     *
     * @param sponsor the sponsor to attach them to, or {@code null} to make them a root
     */
    public SponsorEntry adopt(UUID uuid, String name, UUID sponsor, long now, SponsorStatus status) {
        if (sponsor != null) {
            if (uuid.equals(sponsor)) {
                throw new SponsorGraphException(Reason.SELF_INVITE);
            }
            if (!this.entries.containsKey(sponsor)) {
                throw new SponsorGraphException(Reason.NOT_IN_GRAPH, sponsor);
            }
            if (this.contains(uuid) && this.isDescendantOf(sponsor, uuid)) {
                throw new SponsorGraphException(Reason.WOULD_CREATE_CYCLE, uuid);
            }
        }

        SponsorEntry existing = this.entries.get(uuid);
        if (existing != null) {
            if (existing.getSponsor() != null) {
                this.childrenOf(existing.getSponsor()).remove(uuid);
            }
            existing.setSponsor(sponsor);
            existing.setStatus(status);
            existing.setLastKnownName(name);
        } else {
            existing = new SponsorEntry(uuid, name, sponsor, now, status == SponsorStatus.ACTIVE ? now : 0L, status);
            this.entries.put(uuid, existing);
        }
        if (sponsor != null) {
            this.childrenOf(sponsor).add(uuid);
        }
        return existing;
    }

    /**
     * Withdraws a sponsorship.
     *
     * @param cascade {@code true} to revoke the entire subtree; {@code false} to revoke only the target and re-parent
     *                their direct invitees onto the target's own sponsor (making them roots if the target was a root)
     * @return every UUID whose status changed to {@link SponsorStatus#REVOKED}, the target first
     * @throws SponsorGraphException if the target is not in the tree
     */
    public Set<UUID> revoke(UUID target, boolean cascade, long now) {
        SponsorEntry entry = this.entries.get(target);
        if (entry == null) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, target);
        }

        Set<UUID> revoked = new LinkedHashSet<>();
        if (cascade) {
            for (UUID uuid : this.subtreeOf(target)) {
                SponsorEntry member = this.entries.get(uuid);
                if (member == null) {
                    continue;
                }
                if (member.getStatus().isLive()) {
                    member.setStatus(SponsorStatus.REVOKED);
                    revoked.add(uuid);
                } else if (uuid.equals(target)) {
                    // Already revoked, but callers still need it here to re-run the unwhitelist/kick.
                    revoked.add(uuid);
                }
            }
        } else {
            UUID grandparent = entry.getSponsor();
            for (UUID child : this.directChildren(target)) {
                SponsorEntry childEntry = this.entries.get(child);
                if (childEntry == null) {
                    continue;
                }
                childEntry.setSponsor(grandparent);
                if (grandparent != null) {
                    this.childrenOf(grandparent).add(child);
                }
            }
            this.childrenOf(target).clear();
            entry.setStatus(SponsorStatus.REVOKED);
            revoked.add(target);
        }
        return revoked;
    }

    /**
     * Moves a player (and everything beneath them) under a new sponsor.
     *
     * @throws SponsorGraphException if either player is unknown, or the move would create a cycle
     */
    public void reassign(UUID target, UUID newSponsor) {
        SponsorEntry entry = this.entries.get(target);
        if (entry == null) {
            throw new SponsorGraphException(Reason.NOT_IN_GRAPH, target);
        }
        if (newSponsor != null) {
            if (!this.entries.containsKey(newSponsor)) {
                throw new SponsorGraphException(Reason.NOT_IN_GRAPH, newSponsor);
            }
            if (target.equals(newSponsor) || this.isDescendantOf(newSponsor, target)) {
                throw new SponsorGraphException(Reason.WOULD_CREATE_CYCLE, newSponsor);
            }
        }

        if (entry.getSponsor() != null) {
            this.childrenOf(entry.getSponsor()).remove(target);
        }
        entry.setSponsor(newSponsor);
        if (newSponsor != null) {
            this.childrenOf(newSponsor).add(target);
        }
    }

    /**
     * Refreshes the display name cached for a player.
     *
     * @return {@code true} if the name actually changed, so callers only save when there is something to save
     */
    public boolean refreshName(UUID uuid, String name) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry == null || name == null || name.equals(entry.getLastKnownName())) {
            return false;
        }
        entry.setLastKnownName(name);
        return true;
    }

    /**
     * Marks a pending invite as taken up, which happens the first time the invitee joins.
     *
     * @return {@code true} if this was the transition from PENDING to ACTIVE
     */
    public boolean activate(UUID uuid, long now) {
        SponsorEntry entry = this.entries.get(uuid);
        if (entry == null || entry.getStatus() != SponsorStatus.PENDING) {
            return false;
        }
        entry.setStatus(SponsorStatus.ACTIVE);
        entry.setAcceptedAt(now);
        return true;
    }

    /** Permanently deletes an entry and re-parents its invitees onto its sponsor. */
    public boolean forget(UUID uuid) {
        SponsorEntry entry = this.entries.remove(uuid);
        if (entry == null) {
            return false;
        }
        UUID grandparent = entry.getSponsor();
        for (UUID child : this.directChildren(uuid)) {
            SponsorEntry childEntry = this.entries.get(child);
            if (childEntry != null) {
                childEntry.setSponsor(grandparent);
                if (grandparent != null) {
                    this.childrenOf(grandparent).add(child);
                }
            }
        }
        this.children.remove(uuid);
        if (grandparent != null) {
            this.childrenOf(grandparent).remove(uuid);
        }
        return true;
    }

    /** The direct invitees of a player, in invite order. Includes revoked invitees. */
    public List<UUID> directChildren(UUID sponsor) {
        Set<UUID> set = this.children.get(sponsor);
        return set == null ? List.of() : List.copyOf(set);
    }

    /** Direct invitees whose sponsorship is still live, i.e. what counts against an invite allowance. */
    public int liveInviteCount(UUID sponsor) {
        int count = 0;
        for (UUID child : this.directChildren(sponsor)) {
            SponsorEntry entry = this.entries.get(child);
            if (entry != null && entry.getStatus().isLive()) {
                count++;
            }
        }
        return count;
    }

    /** Epoch millis of this player's most recent invite, or {@code 0} if they have never invited anyone. */
    public long lastInviteAt(UUID sponsor) {
        long latest = 0L;
        for (UUID child : this.directChildren(sponsor)) {
            SponsorEntry entry = this.entries.get(child);
            if (entry != null) {
                latest = Math.max(latest, entry.getInvitedAt());
            }
        }
        return latest;
    }

    /**
     * The player plus everyone beneath them, breadth first. The subtree root is always the first element, so callers
     * that revoke a branch can hand the whole list straight to the whitelist.
     */
    public List<UUID> subtreeOf(UUID root) {
        List<UUID> result = new ArrayList<>();
        if (!this.entries.containsKey(root)) {
            return result;
        }
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            result.add(current);
            for (UUID child : this.directChildren(current)) {
                if (seen.add(child)) {
                    queue.addLast(child);
                }
            }
        }
        return result;
    }

    /**
     * The chain of responsibility for a player: the player first, then their sponsor, and so on up to the root.
     * Returns an empty list if the player is not in the tree.
     */
    public List<SponsorEntry> chainToRoot(UUID uuid) {
        List<SponsorEntry> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = uuid;
        while (current != null && seen.add(current)) {
            SponsorEntry entry = this.entries.get(current);
            if (entry == null) {
                break;
            }
            chain.add(entry);
            current = entry.getSponsor();
        }
        return chain;
    }

    /** Whether {@code candidate} sits anywhere beneath {@code ancestor}. A player is not their own descendant. */
    public boolean isDescendantOf(UUID candidate, UUID ancestor) {
        if (candidate == null || ancestor == null || candidate.equals(ancestor)) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        UUID current = candidate;
        while (current != null && seen.add(current)) {
            SponsorEntry entry = this.entries.get(current);
            if (entry == null) {
                return false;
            }
            UUID sponsor = entry.getSponsor();
            if (ancestor.equals(sponsor)) {
                return true;
            }
            current = sponsor;
        }
        return false;
    }

    public List<SponsorEntry> roots() {
        List<SponsorEntry> roots = new ArrayList<>();
        for (SponsorEntry entry : this.entries.values()) {
            if (entry.isRoot()) {
                roots.add(entry);
            }
        }
        return roots;
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

    /** Longest root-to-leaf path length, counted in players. */
    public int deepestChain() {
        int deepest = 0;
        for (SponsorEntry entry : this.entries.values()) {
            deepest = Math.max(deepest, this.chainToRoot(entry.getUuid()).size());
        }
        return deepest;
    }
}
