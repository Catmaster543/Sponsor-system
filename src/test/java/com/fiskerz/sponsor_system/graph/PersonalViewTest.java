package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The first-degree privacy rule behind {@code /mysponsors}.
 *
 * <p>These are the tests that matter most in this file: a leak here is not a cosmetic bug, it hands players a map of
 * who to pressure. The graph is deliberately built so that every kind of second-degree relationship exists and could
 * be leaked if the gathering code followed one edge too many.
 */
class PersonalViewTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int UNLIMITED = -1;

    private SupportGraph graph;
    /** The player running the command. */
    private UUID caller;
    /** Backs the caller. */
    private UUID inviter;
    /** Also backs the caller. */
    private UUID sponsor;
    /** The caller backs them. */
    private UUID protege;

    /** Second degree, and must never appear: backs the caller's inviter. */
    private UUID inviterOfInviter;
    /** Second degree: also backs the caller's sponsor. */
    private UUID otherSponsorOfSponsor;
    /** Second degree: also backs the caller's protege. */
    private UUID coSponsorOfProtege;
    /** Second degree: the caller's protege backs them. */
    private UUID protegeOfProtege;
    /** Not connected to the caller at all. */
    private UUID stranger;

    @BeforeEach
    void setUp() {
        this.graph = new SupportGraph();
        this.caller = UUID.randomUUID();
        this.inviter = UUID.randomUUID();
        this.sponsor = UUID.randomUUID();
        this.protege = UUID.randomUUID();
        this.inviterOfInviter = UUID.randomUUID();
        this.otherSponsorOfSponsor = UUID.randomUUID();
        this.coSponsorOfProtege = UUID.randomUUID();
        this.protegeOfProtege = UUID.randomUUID();
        this.stranger = UUID.randomUUID();

        // Root of everything, two hops above the caller.
        this.graph.addEntry(this.inviterOfInviter, "GrandSupporter", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.inviterOfInviter, true);
        this.graph.addEntry(this.otherSponsorOfSponsor, "OtherSponsorOfSponsor", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.otherSponsorOfSponsor, true);
        this.graph.addEntry(this.coSponsorOfProtege, "CoSponsorOfProtege", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.coSponsorOfProtege, true);
        this.graph.addEntry(this.stranger, "Stranger", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.stranger, true);

        this.graph.invite(this.inviterOfInviter, this.inviter, "Inviter", NOW, UNLIMITED);
        this.graph.invite(this.inviterOfInviter, this.sponsor, "Sponsor", NOW, UNLIMITED);
        this.graph.sponsor(this.otherSponsorOfSponsor, this.sponsor, NOW, UNLIMITED);

        this.graph.invite(this.inviter, this.caller, "Caller", NOW, UNLIMITED);
        this.graph.sponsor(this.sponsor, this.caller, NOW, UNLIMITED);

        this.graph.invite(this.caller, this.protege, "Protege", NOW, UNLIMITED);
        this.graph.sponsor(this.coSponsorOfProtege, this.protege, NOW, UNLIMITED);
        this.graph.invite(this.protege, this.protegeOfProtege, "ProtegeOfProtege", NOW, UNLIMITED);

        // Everyone in the fixture has actually joined, so restoring support returns them to ACTIVE rather than
        // to the never-seen PENDING state.
        this.graph.activate(this.caller, NOW);
        this.graph.activate(this.protege, NOW);
    }

    private PersonalView view() {
        return PersonalView.of(this.graph, this.caller).orElseThrow();
    }

    @Nested
    @DisplayName("privacy")
    class Privacy {
        @Test
        @DisplayName("nothing beyond the first degree appears anywhere in the view")
        void noSecondDegreeLeak() {
            Set<UUID> referenced = view().referencedPlayers();

            assertFalse(referenced.contains(inviterOfInviter), "who backs the caller's inviter must not leak");
            assertFalse(referenced.contains(otherSponsorOfSponsor), "who else backs the caller's sponsor must not leak");
            assertFalse(referenced.contains(coSponsorOfProtege), "who else backs the caller's protege must not leak");
            assertFalse(referenced.contains(protegeOfProtege), "who the caller's protege backs must not leak");
            assertFalse(referenced.contains(stranger));
        }

        @Test
        @DisplayName("the view contains exactly the caller and their direct neighbours")
        void exactlyFirstDegree() {
            assertEquals(Set.of(caller, inviter, sponsor, protege), view().referencedPlayers());
        }

        @Test
        @DisplayName("both of the caller's supporters are shown, and only those two")
        void showsAllDirectSupporters() {
            List<UUID> supporters = view().supporters().stream().map(SupportEdge::from).toList();

            assertEquals(2, supporters.size());
            assertTrue(supporters.contains(inviter));
            assertTrue(supporters.contains(sponsor));
        }

        @Test
        @DisplayName("only the people the caller directly backs are shown")
        void showsOnlyDirectSupported() {
            List<UUID> supported = view().supported().stream().map(SupportEdge::to).toList();

            assertEquals(List.of(protege), supported);
        }

        @Test
        @DisplayName("adding more second-degree relationships still leaks nothing")
        void staysClosedAsGraphGrows() {
            UUID extra = UUID.randomUUID();
            graph.addEntry(extra, "Extra", NOW, SponsorStatus.ACTIVE);
            graph.markRoot(extra, true);
            graph.sponsor(extra, inviter, NOW, UNLIMITED);
            graph.sponsor(extra, protege, NOW, UNLIMITED);

            assertFalse(view().referencedPlayers().contains(extra));
            assertEquals(Set.of(caller, inviter, sponsor, protege), view().referencedPlayers());
        }

        @Test
        @DisplayName("a cycle through the caller does not pull in extra players")
        void cyclesDoNotLeak() {
            // The caller sponsors their own inviter, which is legal and makes the graph cyclic.
            graph.sponsor(caller, inviter, NOW, UNLIMITED);

            assertEquals(Set.of(caller, inviter, sponsor, protege), view().referencedPlayers());
        }
    }

    @Nested
    @DisplayName("content")
    class Content {
        @Test
        @DisplayName("the inviter is identifiable but not separated out")
        void inviterIsLabelledNotRanked() {
            List<SupportEdge> supporters = view().supporters();

            // One list, in edge order, with the kind available for a label. Nothing here orders or groups by kind.
            assertEquals(2, supporters.size());
            assertEquals(1, supporters.stream().filter(SupportEdge::isPrimary).count());
            assertEquals(inviter, supporters.stream().filter(SupportEdge::isPrimary).findFirst().orElseThrow().from());
        }

        @Test
        @DisplayName("the status of someone the caller backs is first-degree and is shown")
        void showsSupportedStatus() {
            graph.withdraw(caller, protege, NOW, 0L, false);
            graph.withdraw(coSponsorOfProtege, protege, NOW, 0L, false);
            graph.recomputeSupport(SupportModel.REACHABILITY, 1800);
            graph.sponsor(caller, protege, NOW + 1, UNLIMITED);
            graph.recomputeSupport(SupportModel.REACHABILITY, 1800);

            assertEquals(SponsorStatus.ACTIVE, view().statusOf(graph, protege));
        }

        @Test
        @DisplayName("the caller's own grace clock is part of their own standing")
        void reportsOwnGrace() {
            graph.withdraw(inviter, caller, NOW, 0L, false);
            graph.withdraw(sponsor, caller, NOW, 0L, false);
            graph.recomputeSupport(SupportModel.REACHABILITY, 1800);

            PersonalView view = view();

            assertTrue(view.isOnGraceClock());
            assertEquals(1800, view.self().getGraceSecondsRemaining());
            assertTrue(view.supporters().isEmpty());
        }

        @Test
        @DisplayName("a supported player is not on a grace clock")
        void supportedIsNotOnClock() {
            assertFalse(view().isOnGraceClock());
        }

        @Test
        @DisplayName("a player who is not in the graph has no view at all")
        void unknownPlayerHasNoView() {
            assertTrue(PersonalView.of(graph, UUID.randomUUID()).isEmpty());
        }
    }
}
