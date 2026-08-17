package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Tests for the sponsorship tree. No Minecraft, no server — the whole point of keeping the graph free of game imports.
 */
class SponsorGraphTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int UNLIMITED = -1;

    private SponsorGraph graph;
    private UUID root;
    private UUID alice;
    private UUID bob;
    private UUID carol;
    private UUID dave;

    @BeforeEach
    void setUp() {
        this.graph = new SponsorGraph();
        this.root = UUID.randomUUID();
        this.alice = UUID.randomUUID();
        this.bob = UUID.randomUUID();
        this.carol = UUID.randomUUID();
        this.dave = UUID.randomUUID();
        this.graph.adopt(this.root, "Root", null, NOW, SponsorStatus.ACTIVE);
    }

    /** root -> alice -> bob -> carol, plus root -> dave. */
    private void buildChain() {
        this.graph.invite(this.root, this.alice, "Alice", NOW, UNLIMITED);
        this.graph.invite(this.alice, this.bob, "Bob", NOW, UNLIMITED);
        this.graph.invite(this.bob, this.carol, "Carol", NOW, UNLIMITED);
        this.graph.invite(this.root, this.dave, "Dave", NOW, UNLIMITED);
    }

    /** Asserts the action was refused, and hands back why, so each test can name the reason it expects. */
    private Reason reasonOf(Executable action) {
        return assertThrows(SponsorGraphException.class, action).getReason();
    }

    @Nested
    @DisplayName("invite")
    class Invite {
        @Test
        @DisplayName("records the sponsor and starts the invitee as pending")
        void recordsSponsor() {
            SponsorEntry entry = graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            assertEquals(root, entry.getSponsor());
            assertEquals(SponsorStatus.PENDING, entry.getStatus());
            assertEquals(NOW, entry.getInvitedAt());
            assertEquals(0L, entry.getAcceptedAt());
            assertEquals(List.of(alice), graph.directChildren(root));
        }

        @Test
        @DisplayName("refuses a self-invite")
        void refusesSelfInvite() {
            assertEquals(Reason.SELF_INVITE, reasonOf(() -> graph.invite(root, root, "Root", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses someone who already has a sponsor, and names that sponsor")
        void refusesDoubleInvite() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.invite(root, bob, "Bob", NOW, UNLIMITED);

            SponsorGraphException exception = assertThrows(SponsorGraphException.class,
                    () -> graph.invite(bob, alice, "Alice", NOW, UNLIMITED));

            assertEquals(Reason.ALREADY_SPONSORED, exception.getReason());
            assertEquals(root, exception.getSubject(), "the caller should be told who the existing sponsor is");
        }

        @Test
        @DisplayName("refuses a sponsor who is not in the tree")
        void refusesUnknownSponsor() {
            assertEquals(Reason.SPONSOR_NOT_IN_GRAPH,
                    reasonOf(() -> graph.invite(UUID.randomUUID(), alice, "Alice", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("refuses a sponsor whose own sponsorship was revoked")
        void refusesRevokedSponsor() {
            buildChain();
            graph.revoke(alice, true, NOW);

            // Alice can still be online if she is an operator, since operators bypass the whitelist.
            assertEquals(Reason.SPONSOR_REVOKED,
                    reasonOf(() -> graph.invite(alice, UUID.randomUUID(), "Newcomer", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("lets a revoked player be invited again, reusing their row")
        void allowsReinviteAfterRevoke() {
            buildChain();
            graph.revoke(alice, true, NOW);

            SponsorEntry reinvited = graph.invite(dave, alice, "Alice", NOW + 1, UNLIMITED);

            assertEquals(dave, reinvited.getSponsor());
            assertEquals(SponsorStatus.PENDING, reinvited.getStatus());
            assertEquals(1, graph.entries().stream().filter(entry -> entry.getUuid().equals(alice)).count(),
                    "a re-invite must not create a second row for the same UUID");
            assertFalse(graph.directChildren(root).contains(alice), "the old sponsor link must be cleared");
        }
    }

    @Nested
    @DisplayName("invite limits")
    class Limits {
        @Test
        @DisplayName("refuses once the allowance is used up")
        void refusesOverLimit() {
            graph.invite(root, alice, "Alice", NOW, 2);
            graph.invite(root, bob, "Bob", NOW, 2);

            assertEquals(Reason.INVITE_LIMIT_REACHED, reasonOf(() -> graph.invite(root, carol, "Carol", NOW, 2)));
        }

        @Test
        @DisplayName("a negative allowance means unlimited")
        void unlimitedWhenNegative() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.invite(root, bob, "Bob", NOW, UNLIMITED);
            graph.invite(root, carol, "Carol", NOW, UNLIMITED);

            assertEquals(3, graph.liveInviteCount(root));
        }

        @Test
        @DisplayName("a zero allowance refuses everything")
        void zeroAllowanceRefuses() {
            assertEquals(Reason.INVITE_LIMIT_REACHED, reasonOf(() -> graph.invite(root, alice, "Alice", NOW, 0)));
        }

        @Test
        @DisplayName("revoked invitees free up a slot again")
        void revokedDoesNotCount() {
            graph.invite(root, alice, "Alice", NOW, 2);
            graph.invite(root, bob, "Bob", NOW, 2);
            graph.revoke(bob, true, NOW);

            assertEquals(1, graph.liveInviteCount(root));
            // The slot Bob occupied is free, so this must not throw.
            graph.invite(root, carol, "Carol", NOW, 2);
            assertEquals(2, graph.liveInviteCount(root));
        }
    }

    @Nested
    @DisplayName("cascade revoke")
    class CascadeRevoke {
        @Test
        @DisplayName("takes down the whole branch beneath the target")
        void revokesWholeSubtree() {
            buildChain();

            Set<UUID> revoked = graph.revoke(alice, true, NOW);

            assertEquals(List.of(alice, bob, carol), List.copyOf(revoked), "the target must come first");
            assertEquals(SponsorStatus.REVOKED, graph.get(alice).orElseThrow().getStatus());
            assertEquals(SponsorStatus.REVOKED, graph.get(bob).orElseThrow().getStatus());
            assertEquals(SponsorStatus.REVOKED, graph.get(carol).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("leaves branches that hang off a different sponsor alone")
        void leavesSiblingsAlone() {
            buildChain();

            graph.revoke(alice, true, NOW);

            assertEquals(SponsorStatus.PENDING, graph.get(dave).orElseThrow().getStatus());
            assertEquals(SponsorStatus.ACTIVE, graph.get(root).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("keeps the sponsor links so the history stays readable")
        void keepsLinks() {
            buildChain();

            graph.revoke(alice, true, NOW);

            assertEquals(alice, graph.get(bob).orElseThrow().getSponsor());
        }

        @Test
        @DisplayName("refuses a target that is not in the tree")
        void refusesUnknownTarget() {
            assertEquals(Reason.NOT_IN_GRAPH, reasonOf(() -> graph.revoke(UUID.randomUUID(), true, NOW)));
        }
    }

    @Nested
    @DisplayName("orphan re-parenting")
    class OrphanReparenting {
        @Test
        @DisplayName("moves the invitees of a revoked player onto that player's sponsor")
        void reparentsOntoGrandparent() {
            buildChain();

            Set<UUID> revoked = graph.revoke(alice, false, NOW);

            assertEquals(Set.of(alice), revoked, "only the target is revoked when cascade is off");
            assertEquals(root, graph.get(bob).orElseThrow().getSponsor());
            assertEquals(SponsorStatus.PENDING, graph.get(bob).orElseThrow().getStatus());
            assertTrue(graph.directChildren(root).contains(bob));
            assertFalse(graph.directChildren(alice).contains(bob));
        }

        @Test
        @DisplayName("promotes invitees to roots when the revoked player was a root")
        void promotesToRootWhenNoGrandparent() {
            buildChain();

            graph.revoke(root, false, NOW);

            SponsorEntry aliceEntry = graph.get(alice).orElseThrow();
            assertNull(aliceEntry.getSponsor());
            assertTrue(aliceEntry.isRoot());
            assertTrue(graph.roots().stream().anyMatch(entry -> entry.getUuid().equals(alice)));
        }

        @Test
        @DisplayName("keeps grandchildren attached to their own sponsor")
        void leavesDeeperLevelsAlone() {
            buildChain();

            graph.revoke(alice, false, NOW);

            assertEquals(bob, graph.get(carol).orElseThrow().getSponsor());
        }
    }

    @Nested
    @DisplayName("cycle rejection")
    class Cycles {
        @Test
        @DisplayName("reassign refuses to move someone under their own descendant")
        void reassignRefusesCycle() {
            buildChain();

            assertEquals(Reason.WOULD_CREATE_CYCLE, reasonOf(() -> graph.reassign(alice, carol)));
            assertEquals(root, graph.get(alice).orElseThrow().getSponsor(), "the tree must be left untouched");
        }

        @Test
        @DisplayName("reassign refuses to make someone their own sponsor")
        void reassignRefusesSelf() {
            buildChain();

            assertEquals(Reason.WOULD_CREATE_CYCLE, reasonOf(() -> graph.reassign(alice, alice)));
        }

        @Test
        @DisplayName("reassign allows a legitimate move")
        void reassignAllowsValidMove() {
            buildChain();

            graph.reassign(bob, dave);

            assertEquals(dave, graph.get(bob).orElseThrow().getSponsor());
            assertTrue(graph.directChildren(dave).contains(bob));
            assertFalse(graph.directChildren(alice).contains(bob));
            assertEquals(bob, graph.get(carol).orElseThrow().getSponsor(), "the branch moves with them");
        }

        @Test
        @DisplayName("re-inviting a revoked player is refused when the new sponsor sits below them")
        void reinviteRefusesCycle() {
            buildChain();
            // A cascade keeps the sponsor links, so Carol is still recorded underneath Alice after both are revoked.
            graph.revoke(alice, true, NOW);
            // An admin puts Carol back without moving her, leaving a live player below a revoked one.
            graph.adopt(carol, "Carol", alice, NOW, SponsorStatus.ACTIVE);

            assertEquals(Reason.WOULD_CREATE_CYCLE, reasonOf(() -> graph.invite(carol, alice, "Alice", NOW, UNLIMITED)));
        }

        @Test
        @DisplayName("adopt refuses to attach someone under their own descendant")
        void adoptRefusesCycle() {
            buildChain();

            assertEquals(Reason.WOULD_CREATE_CYCLE,
                    reasonOf(() -> graph.adopt(alice, "Alice", carol, NOW, SponsorStatus.ACTIVE)));
        }
    }

    @Nested
    @DisplayName("chain to root")
    class ChainToRoot {
        @Test
        @DisplayName("runs from the player up to the root")
        void listsWholeChain() {
            buildChain();

            List<UUID> chain = graph.chainToRoot(carol).stream().map(SponsorEntry::getUuid).toList();

            assertEquals(List.of(carol, bob, alice, root), chain);
        }

        @Test
        @DisplayName("is just the player themselves for a root")
        void singleEntryForRoot() {
            buildChain();

            assertEquals(List.of(root), graph.chainToRoot(root).stream().map(SponsorEntry::getUuid).toList());
        }

        @Test
        @DisplayName("is empty for someone who is not in the tree")
        void emptyForUnknown() {
            assertTrue(graph.chainToRoot(UUID.randomUUID()).isEmpty());
        }

        @Test
        @DisplayName("reports the deepest chain in the tree")
        void reportsDepth() {
            buildChain();

            assertEquals(4, graph.deepestChain());
        }
    }

    @Nested
    @DisplayName("descendants and subtrees")
    class Descendants {
        @Test
        @DisplayName("isDescendantOf follows the chain upwards")
        void findsIndirectDescendants() {
            buildChain();

            assertTrue(graph.isDescendantOf(carol, root));
            assertTrue(graph.isDescendantOf(carol, alice));
            assertFalse(graph.isDescendantOf(carol, dave));
            assertFalse(graph.isDescendantOf(root, carol));
        }

        @Test
        @DisplayName("nobody is their own descendant")
        void selfIsNotDescendant() {
            buildChain();

            assertFalse(graph.isDescendantOf(alice, alice));
        }

        @Test
        @DisplayName("subtreeOf starts with the player themselves")
        void subtreeIncludesSelf() {
            buildChain();

            assertEquals(List.of(alice, bob, carol), graph.subtreeOf(alice));
            assertEquals(List.of(root, alice, dave, bob, carol), graph.subtreeOf(root));
        }
    }

    @Nested
    @DisplayName("loading a file")
    class Loading {
        @Test
        @DisplayName("promotes an entry whose sponsor is missing, and says so")
        void repairsDanglingSponsor() {
            List<String> warnings = graph.replaceAll(List.of(
                    new SponsorEntry(alice, "Alice", UUID.randomUUID(), NOW, NOW, SponsorStatus.ACTIVE)));

            assertTrue(graph.get(alice).orElseThrow().isRoot());
            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("breaks a cycle that was hand-edited into the file")
        void repairsCycle() {
            SponsorEntry first = new SponsorEntry(alice, "Alice", bob, NOW, NOW, SponsorStatus.ACTIVE);
            SponsorEntry second = new SponsorEntry(bob, "Bob", alice, NOW, NOW, SponsorStatus.ACTIVE);

            List<String> warnings = graph.replaceAll(List.of(first, second));

            assertFalse(warnings.isEmpty());
            // Whichever one was broken, the walk upwards must now terminate.
            assertTrue(graph.chainToRoot(alice).size() <= 2);
            assertTrue(graph.roots().size() >= 1);
        }

        @Test
        @DisplayName("promotes an entry that sponsors itself")
        void repairsSelfSponsor() {
            List<String> warnings = graph.replaceAll(List.of(
                    new SponsorEntry(alice, "Alice", alice, NOW, NOW, SponsorStatus.ACTIVE)));

            assertTrue(graph.get(alice).orElseThrow().isRoot());
            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("rebuilds the child index so the tree is navigable again")
        void rebuildsChildIndex() {
            graph.replaceAll(List.of(
                    new SponsorEntry(root, "Root", null, NOW, NOW, SponsorStatus.ACTIVE),
                    new SponsorEntry(alice, "Alice", root, NOW, NOW, SponsorStatus.ACTIVE),
                    new SponsorEntry(bob, "Bob", alice, NOW, NOW, SponsorStatus.ACTIVE)));

            assertEquals(List.of(alice), graph.directChildren(root));
            assertEquals(List.of(root, alice, bob), graph.subtreeOf(root));
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
            assertEquals(NOW + 5, graph.get(alice).orElseThrow().getAcceptedAt());
            assertFalse(graph.activate(alice, NOW + 9), "a second login must not change anything");
        }

        @Test
        @DisplayName("refreshName only reports a change when the name really changed")
        void refreshesName() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);

            assertFalse(graph.refreshName(alice, "Alice"));
            assertTrue(graph.refreshName(alice, "AliceRenamed"));
            assertEquals("AliceRenamed", graph.get(alice).orElseThrow().getLastKnownName());
        }

        @Test
        @DisplayName("lastInviteAt reports the most recent invite, for the cooldown")
        void tracksLastInvite() {
            assertEquals(0L, graph.lastInviteAt(root));

            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.invite(root, bob, "Bob", NOW + 500, UNLIMITED);

            assertEquals(NOW + 500, graph.lastInviteAt(root));
        }

        @Test
        @DisplayName("pendingInvitedBefore finds invites that were never taken up")
        void findsStaleInvites() {
            graph.invite(root, alice, "Alice", NOW, UNLIMITED);
            graph.invite(root, bob, "Bob", NOW + 10_000, UNLIMITED);
            graph.activate(bob, NOW + 20_000);

            List<SponsorEntry> stale = graph.pendingInvitedBefore(NOW + 5_000);

            assertEquals(1, stale.size());
            assertEquals(alice, stale.get(0).getUuid());
        }
    }
}
