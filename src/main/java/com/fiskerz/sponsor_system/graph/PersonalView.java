package com.fiskerz.sponsor_system.graph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything one player is allowed to see about their own position in the graph.
 *
 * <p><strong>This class is the privacy boundary.</strong> It gathers exactly two things — who backs the caller, and
 * who the caller backs — and nothing else. It never follows an edge twice. If a future change wants to show more, it
 * has to happen here, in one auditable place, rather than leaking in gradually through rendering code.
 *
 * <p>The reason for the restriction: a player who could see their neighbours' neighbours could walk the graph one hop
 * at a time and rebuild the whole thing, which turns a record of responsibility into a map of who to pressure.
 * First-degree only means a player learns their own obligations and gains nothing over strangers.
 *
 * <p>No Minecraft imports, so {@link #referencedPlayers()} can be asserted against in a unit test.
 */
public record PersonalView(SponsorEntry self, List<SupportEdge> supporters, List<SupportEdge> supported) {

    /**
     * Builds the view for one player.
     *
     * @return empty if that player is not in the graph
     */
    public static Optional<PersonalView> of(SupportGraph graph, UUID caller) {
        return graph.get(caller).map(entry ->
                new PersonalView(entry, graph.edgesTo(caller), graph.edgesFrom(caller)));
    }

    /** Whether the caller is on a grace clock right now. */
    public boolean isOnGraceClock() {
        return this.self.getStatus() == SponsorStatus.ABANDONED && this.self.hasGraceClock();
    }

    /** The status of one player the caller backs — first-degree information about someone they answer for. */
    public SponsorStatus statusOf(SupportGraph graph, UUID supportedPlayer) {
        return graph.get(supportedPlayer).map(SponsorEntry::getStatus).orElse(SponsorStatus.EXPIRED);
    }

    /**
     * Every player who appears anywhere in this view, including the caller.
     *
     * <p>Exists so the privacy rule can be asserted rather than merely intended: this set must never contain anybody
     * more than one edge away from the caller.
     */
    public Set<UUID> referencedPlayers() {
        Set<UUID> referenced = new LinkedHashSet<>();
        referenced.add(this.self.getUuid());
        this.supporters.forEach(edge -> referenced.add(edge.from()));
        this.supported.forEach(edge -> referenced.add(edge.to()));
        return referenced;
    }
}
