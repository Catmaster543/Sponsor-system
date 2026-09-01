package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.fiskerz.sponsor_system.graph.SponsorGraphException.Reason;

/**
 * Tests for the support graph: the edge model, the two support models, and the ticket accounting.
 *
 * <p>No Minecraft, no server. This is where the bugs would live, so it is where the tests are.
 */
class SupportGraphTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int UNLIMITED = -1;
    private static final long NO_MIN_AGE = 0L;

    private SupportGraph graph;
    private UUID root;
    private UUID alice;
    private UUID bob;
    private UUID carol;
    private UUID dave;

    @BeforeEach
    void setUp() {
        this.graph = new SupportGraph();
        this.root = UUID.randomUUID();
        this.alice = UUID.randomUUID();
        this.bob = UUID.randomUUID();
        this.carol = UUID.randomUUID();
        this.dave = UUID.randomUUID();
        this.graph.addEntry(this.root, "Root", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.root, true);
    }

    /** root -> alice -> bob -> carol, plus root -> dave. All PRIMARY. */
    private void buildChain() {
        this.graph.invite(this.root, this.alice, "Alice", NOW, UNLIMITED);
        this.graph.invite(this.alice, this.bob, "Bob", NOW, UNLIMITED);
        this.graph.invite(this.bob, this.carol, "Carol", NOW, UNLIMITED);
        this.graph.invite(this.root, this.dave, "Dave", NOW, UNLIMITED);
    }

    private Reason reasonOf(Executable action) {
        return assertThrows(SponsorGraphException.class, action).getReason();
    }

    private Set<UUID> supported() {
        return this.graph.computeSupported(SupportModel.REACHABILITY);
    }

    @Nested
    @DisplayName("invite")
    class Invite {
        @Test
        @DisplayName("creates the player and one primary edge")
        void createsPrimaryEdge() {
            SupportEdge edge = graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            assertEquals(EdgeKind.PRIMARY, edge.kind());
            assertTrue(graph.contains(alice));
            assertEquals(SponsorStatus.PENDING, graph.get(alice).orElseThrow().getStatus());
            assertEquals(root, graph.inviterOf(alice).orElseThrow());
            assertFalse(graph.get(alice).orElseThrow().isRoot(), "an invited player is not an anchor");
        }

        @Test
        @DisplayName("refuses someone who is already here, pointing at /sponsor instead")
        void refusesExistingPlayer() {
            buildChain();

            assertEquals(Reason.ALREADY_IN_GRAPH, reasonOf(() -> graph.invite(dave, alice, "Alice", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses a self-invite")
        void refusesSelf() {
            assertEquals(Reason.SELF_SUPPORT, reasonOf(() -> graph.invite(root, root, "Root", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses a supporter who is not in the graph")
        void refusesUnknownSupporter() {
            assertEquals(Reason.SUPPORTER_NOT_IN_GRAPH,
                    reasonOf(() -> graph.invite(UUID.randomUUID(), alice, "Alice", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses a supporter who has been revoked")
        void refusesRevokedSupporter() {
            buildChain();
            graph.revoke(alice);

            assertEquals(Reason.SUPPORTER_REVOKED,
                    reasonOf(() -> graph.invite(alice, UUID.randomUUID(), "New", NOW, UNLIMITED)));
        }
    }

    @Nested
    @DisplayName("sponsor")
    class Sponsor {
        @Test
        @DisplayName("adds a second supporter without touching the first")
        void addsSecondSupporter() {
            buildChain();

            SupportEdge edge = graph.sponsor(dave, bob, NOW, UNLIMITED);

            assertEquals(EdgeKind.SPONSORSHIP, edge.kind());
            assertEquals(List.of(alice, dave), graph.supportersOf(bob));
            assertEquals(alice, graph.inviterOf(bob).orElseThrow(), "the primary edge is unchanged");
        }

        @Test
        @DisplayName("refuses someone who is not here yet, pointing at /invite instead")
        void refusesAbsentPlayer() {
            assertEquals(Reason.NOT_IN_GRAPH, reasonOf(() -> graph.sponsor(root, UUID.randomUUID(), NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses a duplicate edge")
        void refusesDuplicate() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            assertEquals(Reason.DUPLICATE_EDGE, reasonOf(() -> graph.sponsor(dave, bob, NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("sponsoring your own ancestor is legal and creates a cycle")
        void ancestorSponsorshipIsLegal() {
            buildChain();

            graph.sponsor(carol, alice, NOW, UNLIMITED);

            assertTrue(graph.supportersOf(alice).contains(carol));
            // Everyone is still reachable from the root, and the traversal terminates.
            assertEquals(5, supported().size());
            assertTrue(graph.descendantsOf(alice).contains(carol));
        }
    }

    @Nested
    @DisplayName("REACHABILITY")
    class Reachability {
        @Test
        @DisplayName("everyone invited down from the root is supported")
        void wholeChainSupported() {
            buildChain();

            assertEquals(Set.of(root, alice, bob, carol, dave), supported());
        }

        @Test
        @DisplayName("removing one edge near the root strands the whole branch below it")
        void branchWideAbandonment() {
            buildChain();

            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            SupportGraph.SupportChange change = graph.recomputeSupport(SupportModel.REACHABILITY);

            assertEquals(Set.of(alice, bob, carol), change.abandoned());
            assertEquals(SponsorStatus.ABANDONED, graph.get(bob).orElseThrow().getStatus());
            assertEquals(SponsorStatus.ABANDONED, graph.get(carol).orElseThrow().getStatus());
            assertEquals(SponsorStatus.PENDING, graph.get(dave).orElseThrow().getStatus(), "a sibling branch is untouched");
        }

        @Test
        @DisplayName("a mutual-sponsorship clique cannot hold itself up")
        void mutualCliqueCollapses() {
            buildChain();
            // Bob and Dave prop each other up, and then both of their real supporters walk away.
            graph.sponsor(bob, dave, NOW, UNLIMITED);
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            graph.withdraw(alice, bob, NOW, NO_MIN_AGE, false);
            graph.withdraw(root, dave, NOW, NO_MIN_AGE, false);
            SupportGraph.SupportChange change = graph.recomputeSupport(SupportModel.REACHABILITY);

            // Each still has an incoming edge - from the other - but nothing reaches either of them from a root.
            assertFalse(graph.edgesTo(bob).isEmpty());
            assertFalse(graph.edgesTo(dave).isEmpty());
            assertTrue(change.abandoned().contains(bob));
            assertTrue(change.abandoned().contains(dave));
            assertTrue(change.abandoned().contains(carol), "and everyone below them goes too");
        }

        @Test
        @DisplayName("a player whose last supporter leaves is abandoned, not promoted to root")
        void lastSupporterLeavingAbandons() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            graph.recomputeSupport(SupportModel.REACHABILITY);

            assertTrue(graph.edgesTo(alice).isEmpty());
            assertFalse(graph.get(alice).orElseThrow().isRoot(), "having no edges is not the same as being an anchor");
            assertEquals(SponsorStatus.ABANDONED, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("a revoked player carries no support onward")
        void revokedCarriesNothing() {
            buildChain();

            graph.revoke(alice);
            SupportGraph.SupportChange change = graph.recomputeSupport(SupportModel.REACHABILITY);

            assertTrue(change.abandoned().contains(bob));
            assertTrue(change.abandoned().contains(carol));
            assertFalse(supported().contains(alice));
        }

        @Test
        @DisplayName("a new sponsorship restores a whole stranded branch at once")
        void restoresBranch() {
            buildChain();
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            graph.recomputeSupport(SupportModel.REACHABILITY);

            graph.sponsor(dave, alice, NOW + 1, UNLIMITED);
            SupportGraph.SupportChange change = graph.recomputeSupport(SupportModel.REACHABILITY);

            assertEquals(Set.of(alice, bob, carol), change.restored());
            assertEquals(SponsorStatus.PENDING, graph.get(carol).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("restoring returns a player who had joined to ACTIVE, not PENDING")
        void restoresToActiveWhenAlreadyJoined() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.activate(alice, NOW + 10);
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            graph.recomputeSupport(SupportModel.REACHABILITY);

            graph.sponsor(root, alice, NOW + 20, UNLIMITED);
            graph.recomputeSupport(SupportModel.REACHABILITY);

            assertEquals(SponsorStatus.ACTIVE, graph.get(alice).orElseThrow().getStatus());
        }
    }

    @Nested
    @DisplayName("DIRECT")
    class Direct {
        @Test
        @DisplayName("a mutual-sponsorship clique survives their inviter withdrawing")
        void mutualCliqueSurvives() {
            buildChain();
            graph.sponsor(bob, dave, NOW, UNLIMITED);
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            graph.withdraw(alice, bob, NOW, NO_MIN_AGE, false);
            graph.withdraw(root, dave, NOW, NO_MIN_AGE, false);
            Set<UUID> supported = graph.computeSupported(SupportModel.DIRECT);

            // This is exactly the hole the config comment warns about: they hold each other up and nobody upstream
            // can do anything about it.
            assertTrue(supported.contains(bob));
            assertTrue(supported.contains(dave));
        }

        @Test
        @DisplayName("a player with no edges at all is still abandoned")
        void noEdgesIsStillAbandoned() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);

            assertFalse(graph.computeSupported(SupportModel.DIRECT).contains(alice));
        }

        @Test
        @DisplayName("the root is supported even though nobody backs it")
        void rootIsSupported() {
            assertTrue(graph.computeSupported(SupportModel.DIRECT).contains(root));
        }

        @Test
        @DisplayName("an edge from a revoked player still counts")
        void revokedSupporterStillCounts() {
            buildChain();
            graph.revoke(alice);

            // Alice's edge to Bob survives her revocation, and under DIRECT that is enough for Bob.
            assertTrue(graph.computeSupported(SupportModel.DIRECT).contains(bob));
            assertFalse(graph.computeSupported(SupportModel.REACHABILITY).contains(bob),
                    "the same graph under REACHABILITY strands Bob");
        }
    }

    @Nested
    @DisplayName("withdrawal and promotion")
    class Withdrawal {
        @Test
        @DisplayName("removes only the caller's own edge")
        void removesOwnEdgeOnly() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            graph.withdraw(dave, bob, NOW, NO_MIN_AGE, false);

            assertEquals(List.of(alice), graph.supportersOf(bob));
        }

        @Test
        @DisplayName("refuses when there is no such edge")
        void refusesMissingEdge() {
            buildChain();

            assertEquals(Reason.NO_SUCH_EDGE, reasonOf(() -> graph.withdraw(dave, bob, NOW, NO_MIN_AGE, false)));
        }

        @Test
        @DisplayName("a sponsorship younger than the minimum cannot be withdrawn")
        void refusesYoungSponsorship() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);
            long tenMinutes = 10L * 60_000L;

            assertEquals(Reason.SPONSORSHIP_TOO_YOUNG,
                    reasonOf(() -> graph.withdraw(dave, bob, NOW + 1000L, tenMinutes, false)));
        }

        @Test
        @DisplayName("the same sponsorship can be withdrawn once it is old enough")
        void allowsOldSponsorship() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);
            long tenMinutes = 10L * 60_000L;

            graph.withdraw(dave, bob, NOW + tenMinutes + 1L, tenMinutes, false);

            assertEquals(List.of(alice), graph.supportersOf(bob));
        }

        @Test
        @DisplayName("an operator bypasses the minimum duration")
        void operatorBypassesAgeCheck() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            graph.withdraw(dave, bob, NOW + 1000L, 10L * 60_000L, true);

            assertEquals(List.of(alice), graph.supportersOf(bob));
        }

        @Test
        @DisplayName("the age check never applies to a primary edge")
        void primaryEdgeIgnoresAgeCheck() {
            buildChain();

            graph.withdraw(alice, bob, NOW + 1L, 10L * 60_000L, false);

            assertTrue(graph.supportersOf(bob).isEmpty());
        }

        @Test
        @DisplayName("losing the primary edge promotes the longest-standing sponsorship")
        void promotesOldestSponsorship() {
            buildChain();
            graph.sponsor(dave, bob, NOW + 500L, UNLIMITED);
            graph.sponsor(carol, bob, NOW + 100L, UNLIMITED);

            graph.withdraw(alice, bob, NOW + 1000L, NO_MIN_AGE, false);
            SupportEdge promoted = graph.promoteOldestSponsorship(bob).orElseThrow();

            assertEquals(carol, promoted.from(), "the oldest remaining supporter, not the newest");
            assertEquals(EdgeKind.PRIMARY, promoted.kind());
            assertEquals(carol, graph.inviterOf(bob).orElseThrow());
            assertEquals(2, graph.edgesTo(bob).size(), "promotion converts an edge, it does not add one");
        }

        @Test
        @DisplayName("promotion does nothing when a primary edge still exists")
        void noPromotionWhilePrimaryExists() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            assertTrue(graph.promoteOldestSponsorship(bob).isEmpty());
            assertEquals(alice, graph.inviterOf(bob).orElseThrow());
        }

        @Test
        @DisplayName("promotion does nothing when nobody is left")
        void noPromotionWithNoSupporters() {
            buildChain();
            graph.withdraw(alice, bob, NOW, NO_MIN_AGE, false);

            assertTrue(graph.promoteOldestSponsorship(bob).isEmpty());
        }

        @Test
        @DisplayName("the dry run agrees with what the real recompute does")
        void dryRunMatchesReality() {
            buildChain();

            boolean predicted = !graph.computeSupportedIgnoring(SupportModel.REACHABILITY, root, alice).contains(alice);
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            boolean actual = graph.recomputeSupport(SupportModel.REACHABILITY).abandoned().contains(alice);

            assertTrue(predicted);
            assertEquals(predicted, actual);
        }

        @Test
        @DisplayName("the dry run says no when another supporter remains")
        void dryRunSeesOtherSupporters() {
            buildChain();
            graph.sponsor(dave, alice, NOW, UNLIMITED);

            assertTrue(graph.computeSupportedIgnoring(SupportModel.REACHABILITY, root, alice).contains(alice));
        }
    }

    @Nested
    @DisplayName("tickets")
    class Tickets {
        @Test
        @DisplayName("each edge spends one, of either kind")
        void spendsOnePerEdge() {
            buildChain();

            assertEquals(2, graph.spentTickets(root), "root invited Alice and Dave");
            graph.sponsor(root, bob, NOW, UNLIMITED);
            assertEquals(3, graph.spentTickets(root));
            assertEquals(2, graph.spentInvites(root));
            assertEquals(1, graph.spentSponsorships(root));
        }

        @Test
        @DisplayName("withdrawing refunds the ticket")
        void withdrawalRefunds() {
            buildChain();
            graph.sponsor(root, bob, NOW, UNLIMITED);

            graph.withdraw(root, bob, NOW, NO_MIN_AGE, false);

            assertEquals(2, graph.spentTickets(root));
        }

        @Test
        @DisplayName("an edge to a revoked player stops costing anything")
        void revokedTargetRefunds() {
            buildChain();
            assertEquals(2, graph.spentTickets(root));

            graph.revoke(dave);

            assertEquals(1, graph.spentTickets(root),
                    "nobody should keep paying for a player an operator removed");
        }

        @Test
        @DisplayName("an edge to a forgotten player stops costing anything")
        void forgottenTargetRefunds() {
            buildChain();

            graph.forget(dave);

            assertEquals(1, graph.spentTickets(root));
        }

        @Test
        @DisplayName("the graph refuses when the caller has nothing left to spend")
        void refusesWithoutTickets() {
            assertEquals(Reason.NO_TICKETS_LEFT, reasonOf(() -> graph.invite(root, alice, "Alice", NOW, 0)));
        }

        @Test
        @DisplayName("a negative allowance means unlimited")
        void negativeMeansUnlimited() {
            buildChain();
            graph.sponsor(root, bob, NOW, UNLIMITED);
            graph.sponsor(root, carol, NOW, UNLIMITED);

            assertEquals(4, graph.spentTickets(root));
        }
    }

    @Nested
    @DisplayName("traversal safety")
    class Traversal {
        @Test
        @DisplayName("descendantsOf terminates on a cycle")
        void descendantsTerminate() {
            buildChain();
            graph.sponsor(carol, root, NOW, UNLIMITED);

            List<UUID> all = graph.descendantsOf(root);

            assertEquals(5, all.size());
            assertEquals(5, Set.copyOf(all).size(), "no duplicates");
        }

        @Test
        @DisplayName("inviterChain terminates on a promoted cycle")
        void inviterChainTerminates() {
            buildChain();
            // Force a cycle into the skeleton: Carol sponsors Root, then Root loses its anchor and Carol is promoted.
            graph.sponsor(carol, root, NOW, UNLIMITED);
            graph.markRoot(root, false);
            graph.promoteOldestSponsorship(root);

            List<SponsorEntry> chain = graph.inviterChain(carol);

            assertTrue(chain.size() <= graph.size());
            assertEquals(chain.size(), Set.copyOf(chain).size(), "no repeats");
        }

        @Test
        @DisplayName("isDescendantOf is false for oneself")
        void notOwnDescendant() {
            buildChain();

            assertFalse(graph.isDescendantOf(alice, alice));
            assertTrue(graph.isDescendantOf(carol, root));
        }

        @Test
        @DisplayName("deepestChain terminates on a cycle")
        void deepestChainTerminates() {
            buildChain();
            graph.sponsor(carol, root, NOW, UNLIMITED);
            graph.markRoot(root, false);
            graph.promoteOldestSponsorship(root);

            assertTrue(graph.deepestChain() <= graph.size());
        }
    }

    @Nested
    @DisplayName("loading")
    class Loading {
        @Test
        @DisplayName("drops edges pointing at players who are not in the file")
        void dropsDanglingEdges() {
            SponsorEntry a = new SponsorEntry(alice, "Alice", NOW, NOW, SponsorStatus.ACTIVE, true);
            List<String> warnings = graph.replaceAll(List.of(a),
                    List.of(new SupportEdge(alice, UUID.randomUUID(), EdgeKind.SPONSORSHIP, NOW)));

            assertEquals(0, graph.edgeCount());
            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("drops a self-supporting edge")
        void dropsSelfEdge() {
            SponsorEntry a = new SponsorEntry(alice, "Alice", NOW, NOW, SponsorStatus.ACTIVE, true);
            List<String> warnings = graph.replaceAll(List.of(a),
                    List.of(new SupportEdge(alice, alice, EdgeKind.SPONSORSHIP, NOW)));

            assertEquals(0, graph.edgeCount());
            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("demotes a second primary edge rather than dropping the relationship")
        void demotesSecondPrimary() {
            SponsorEntry r = new SponsorEntry(root, "Root", NOW, NOW, SponsorStatus.ACTIVE, true);
            SponsorEntry a = new SponsorEntry(alice, "Alice", NOW, NOW, SponsorStatus.ACTIVE, false);
            SponsorEntry b = new SponsorEntry(bob, "Bob", NOW, NOW, SponsorStatus.ACTIVE, false);

            List<String> warnings = graph.replaceAll(List.of(r, a, b), List.of(
                    new SupportEdge(root, bob, EdgeKind.PRIMARY, NOW),
                    new SupportEdge(alice, bob, EdgeKind.PRIMARY, NOW)));

            assertEquals(2, graph.edgeCount(), "both relationships survive");
            assertEquals(root, graph.inviterOf(bob).orElseThrow());
            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("preserves a cycle written into the file")
        void preservesCycles() {
            SponsorEntry a = new SponsorEntry(alice, "Alice", NOW, NOW, SponsorStatus.ACTIVE, false);
            SponsorEntry b = new SponsorEntry(bob, "Bob", NOW, NOW, SponsorStatus.ACTIVE, false);

            List<String> warnings = graph.replaceAll(List.of(a, b), List.of(
                    new SupportEdge(alice, bob, EdgeKind.PRIMARY, NOW),
                    new SupportEdge(bob, alice, EdgeKind.SPONSORSHIP, NOW)));

            assertTrue(warnings.isEmpty(), "a cycle is legal, not a defect");
            assertEquals(2, graph.edgeCount());
            // Neither is an anchor, so under reachability nothing holds them up.
            assertTrue(graph.computeSupported(SupportModel.REACHABILITY).isEmpty());
        }
    }

    @Nested
    @DisplayName("login bookkeeping")
    class Login {
        @Test
        @DisplayName("activate flips a pending invite exactly once")
        void activatesOnce() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            assertTrue(graph.activate(alice, NOW + 5));
            assertEquals(SponsorStatus.ACTIVE, graph.get(alice).orElseThrow().getStatus());
            assertFalse(graph.activate(alice, NOW + 9));
        }

        @Test
        @DisplayName("refreshName only reports a real change")
        void refreshesName() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            assertFalse(graph.refreshName(alice, "Alice"));
            assertTrue(graph.refreshName(alice, "AliceRenamed"));
            assertEquals("AliceRenamed", graph.get(alice).orElseThrow().getLastKnownName());
        }

        @Test
        @DisplayName("lastInviteAt tracks only invites, not sponsorships")
        void lastInviteIgnoresSponsorships() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.invite(root, bob, "Bob", NOW + 500, UNLIMITED);
            graph.invite(alice, carol, "Carol", NOW + 700, UNLIMITED);
            graph.sponsor(root, carol, NOW + 9000, UNLIMITED);

            assertEquals(NOW + 500, graph.lastInviteAt(root));
        }

        @Test
        @DisplayName("a recompute reports nothing when nothing changed")
        void quietRecompute() {
            buildChain();
            graph.recomputeSupport(SupportModel.REACHABILITY);

            assertTrue(graph.recomputeSupport(SupportModel.REACHABILITY).isEmpty());
        }
    }

    @Nested
    @DisplayName("operator edits")
    class OperatorEdits {
        @Test
        @DisplayName("reassign moves the primary edge and keeps the branch")
        void reassignMovesPrimary() {
            buildChain();

            graph.reassignPrimary(bob, dave, NOW + 1);

            assertEquals(dave, graph.inviterOf(bob).orElseThrow());
            assertFalse(graph.supportersOf(bob).contains(alice));
            assertEquals(bob, graph.inviterOf(carol).orElseThrow(), "the branch below moves with them");
            assertTrue(supported().contains(carol));
        }

        @Test
        @DisplayName("reassign promotes an existing sponsorship instead of adding a second edge")
        void reassignPromotesExisting() {
            buildChain();
            graph.sponsor(dave, bob, NOW, UNLIMITED);

            graph.reassignPrimary(bob, dave, NOW + 1);

            assertEquals(1, graph.edgesTo(bob).size());
            assertEquals(EdgeKind.PRIMARY, graph.edgesTo(bob).get(0).kind());
        }

        @Test
        @DisplayName("revoke drops every edge into the player but keeps their own outgoing edges")
        void revokeDropsIncoming() {
            buildChain();

            graph.revoke(alice);

            assertTrue(graph.edgesTo(alice).isEmpty());
            assertEquals(1, graph.edgesFrom(alice).size(), "Alice still shows as having invited Bob");
            assertEquals(SponsorStatus.REVOKED, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("a revoked player is never restored by a later recompute")
        void revokedStaysRevoked() {
            buildChain();
            graph.revoke(alice);

            graph.recomputeSupport(SupportModel.REACHABILITY);

            assertEquals(SponsorStatus.REVOKED, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("forget removes the player and every edge touching them")
        void forgetRemovesEverything() {
            buildChain();

            assertTrue(graph.forget(bob));

            assertFalse(graph.contains(bob));
            assertTrue(graph.edgesFrom(alice).isEmpty());
            assertTrue(graph.edgesTo(carol).isEmpty());
        }

        @Test
        @DisplayName("adoptEdge becomes primary only when there is no primary yet")
        void adoptEdgeKind() {
            buildChain();

            assertEquals(EdgeKind.SPONSORSHIP, graph.adoptEdge(dave, bob, NOW).kind());

            graph.forget(carol);
            SponsorEntry fresh = graph.addEntry(carol, "Carol", NOW, SponsorStatus.ACTIVE);
            assertSame(fresh, graph.get(carol).orElseThrow());
            assertEquals(EdgeKind.PRIMARY, graph.adoptEdge(dave, carol, NOW).kind());
        }
    }
}
