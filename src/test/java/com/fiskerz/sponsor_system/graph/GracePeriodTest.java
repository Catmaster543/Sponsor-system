package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The abandonment clock: starting, pausing, surviving a restart, being cleared by a rescue, and running out.
 *
 * <p>The clock counts playtime, which in the graph means it only moves when somebody calls
 * {@link SupportGraph#decrementGrace}. "Being offline" is modelled here as simply not calling it — which is exactly
 * what the server does, and is why an offline player's time cannot drain.
 */
class GracePeriodTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int UNLIMITED = -1;
    private static final long NO_MIN_AGE = 0L;
    /** 30 minutes, the default. */
    private static final int FULL_GRACE = 30 * 60;

    private SupportGraph graph;
    private UUID root;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        this.graph = new SupportGraph();
        this.root = UUID.randomUUID();
        this.alice = UUID.randomUUID();
        this.bob = UUID.randomUUID();
        this.carol = UUID.randomUUID();
        this.graph.addEntry(this.root, "Root", NOW, SponsorStatus.ACTIVE);
        this.graph.markRoot(this.root, true);
        this.graph.invite(this.root, this.alice, "Alice", NOW, UNLIMITED);
        this.graph.invite(this.alice, this.bob, "Bob", NOW, UNLIMITED);
        this.graph.invite(this.bob, this.carol, "Carol", NOW, UNLIMITED);
        this.graph.activate(this.alice, NOW);
        this.graph.activate(this.bob, NOW);
        this.graph.activate(this.carol, NOW);
    }

    private SupportGraph.SupportChange recompute() {
        return this.graph.recomputeSupport(SupportModel.REACHABILITY, FULL_GRACE);
    }

    private int graceOf(UUID uuid) {
        return this.graph.get(uuid).orElseThrow().getGraceSecondsRemaining();
    }

    @Nested
    @DisplayName("starting the clock")
    class Starting {
        @Test
        @DisplayName("losing support starts a full grace period")
        void abandonmentStartsClock() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);

            recompute();

            assertEquals(SponsorStatus.ABANDONED, graph.get(alice).orElseThrow().getStatus());
            assertEquals(FULL_GRACE, graceOf(alice));
        }

        @Test
        @DisplayName("everyone stranded by one withdrawal gets their own full clock")
        void wholeBranchGetsClocks() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);

            SupportGraph.SupportChange change = recompute();

            assertEquals(Set.of(alice, bob, carol), change.abandoned());
            assertEquals(FULL_GRACE, graceOf(alice));
            assertEquals(FULL_GRACE, graceOf(bob));
            assertEquals(FULL_GRACE, graceOf(carol));
        }

        @Test
        @DisplayName("a later recompute does not restart a clock that is already running")
        void doesNotResetRunningClock() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.decrementGrace(alice, 600);

            // Something unrelated happens elsewhere in the graph.
            graph.invite(root, UUID.randomUUID(), "Newcomer", NOW, UNLIMITED);
            recompute();

            assertEquals(FULL_GRACE - 600, graceOf(alice), "an unrelated change must not hand out fresh time");
        }

        @Test
        @DisplayName("a supported player has no clock at all")
        void supportedHasNoClock() {
            recompute();

            assertFalse(graph.get(alice).orElseThrow().hasGraceClock());
            assertEquals(SponsorEntry.NO_GRACE, graceOf(alice));
        }
    }

    @Nested
    @DisplayName("counting down")
    class CountingDown {
        @Test
        @DisplayName("each tick burns exactly the seconds it is given")
        void decrementsBySeconds() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();

            assertEquals(FULL_GRACE - 1, graph.decrementGrace(alice, 1));
            assertEquals(FULL_GRACE - 2, graph.decrementGrace(alice, 1));
        }

        @Test
        @DisplayName("the clock stops at zero rather than going negative")
        void clampsAtZero() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();

            assertEquals(0, graph.decrementGrace(alice, FULL_GRACE + 500));
            assertEquals(0, graph.decrementGrace(alice, 10));
        }

        @Test
        @DisplayName("time cannot drain from a player who is offline")
        void offlineDoesNotDrain() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.decrementGrace(alice, 60);
            int atLogout = graceOf(alice);

            // An hour of wall-clock passes with the player offline. The server ticks the whole time, but it only
            // decrements players it finds online, so nothing at all happens to this entry.
            for (int second = 0; second < 3600; second++) {
                // deliberately no decrementGrace call
            }

            assertEquals(atLogout, graceOf(alice), "an offline player must lose no time");
            assertEquals(FULL_GRACE - 60, graceOf(alice));
        }

        @Test
        @DisplayName("decrementing a player with no clock does nothing")
        void noClockIsANoOp() {
            assertEquals(SponsorEntry.NO_GRACE, graph.decrementGrace(alice, 30));
        }
    }

    @Nested
    @DisplayName("rescue")
    class Rescue {
        @Test
        @DisplayName("regaining support clears the clock outright")
        void rescueClearsClock() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.decrementGrace(alice, 25 * 60);
            assertEquals(5 * 60, graceOf(alice));

            graph.sponsor(root, alice, NOW + 1, UNLIMITED);
            SupportGraph.SupportChange change = recompute();

            assertTrue(change.restored().contains(alice));
            assertFalse(graph.get(alice).orElseThrow().hasGraceClock());
            assertEquals(SponsorStatus.ACTIVE, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("a later abandonment starts from a full window, not the burnt-down one")
        void nextAbandonmentIsFull() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.decrementGrace(alice, 29 * 60);
            graph.sponsor(root, alice, NOW + 1, UNLIMITED);
            recompute();

            graph.withdraw(root, alice, NOW + 2, NO_MIN_AGE, false);
            recompute();

            assertEquals(FULL_GRACE, graceOf(alice), "surviving once must not leave you one edge from removal");
        }

        @Test
        @DisplayName("rescuing the top of a branch clears every clock below it too")
        void rescueClearsWholeBranch() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.decrementGrace(bob, 100);

            graph.sponsor(root, alice, NOW + 1, UNLIMITED);
            SupportGraph.SupportChange change = recompute();

            assertEquals(Set.of(alice, bob, carol), change.restored());
            assertFalse(graph.get(bob).orElseThrow().hasGraceClock());
        }
    }

    @Nested
    @DisplayName("expiry and its cascade")
    class Expiry {
        @Test
        @DisplayName("expiring removes the player from the live set")
        void expiredIsNotLive() {
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();

            graph.expire(alice);

            assertEquals(SponsorStatus.EXPIRED, graph.get(alice).orElseThrow().getStatus());
            assertFalse(graph.get(alice).orElseThrow().getStatus().isLive());
            assertFalse(graph.get(alice).orElseThrow().hasGraceClock());
        }

        @Test
        @DisplayName("the cascade falls out of the support model, with no cascade code")
        void expiryCascades() {
            // Alice is abandoned; Bob and Carol are only reachable through her, so they go with her.
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            // Rescue the two below her so they are healthy again, and only Alice is on a clock.
            graph.sponsor(root, bob, NOW + 1, UNLIMITED);
            recompute();
            assertEquals(SponsorStatus.ACTIVE, graph.get(bob).orElseThrow().getStatus());

            // Now Alice's clock runs out. Nothing here touches Bob or Carol.
            graph.expire(alice);
            graph.withdraw(root, bob, NOW + 2, NO_MIN_AGE, false);
            SupportGraph.SupportChange change = recompute();

            // Bob's remaining route to a root ran through Alice, who is no longer live, so support cannot flow.
            assertTrue(change.abandoned().contains(bob));
            assertTrue(change.abandoned().contains(carol));
            assertEquals(FULL_GRACE, graceOf(bob), "each gets their own fresh window");
            assertEquals(FULL_GRACE, graceOf(carol));
        }

        @Test
        @DisplayName("an expired player carries no support onward, exactly like a revoked one")
        void expiredCarriesNothing() {
            graph.expire(alice);

            Set<UUID> supported = graph.computeSupported(SupportModel.REACHABILITY);

            assertFalse(supported.contains(alice));
            assertFalse(supported.contains(bob), "Bob's only route ran through Alice");
            assertFalse(supported.contains(carol));
        }

        @Test
        @DisplayName("an expired player is never quietly restored by a later recompute")
        void expiredStaysExpired() {
            graph.expire(alice);

            recompute();

            assertEquals(SponsorStatus.EXPIRED, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("an edge to an expired player refunds its ticket")
        void expiredTargetRefunds() {
            assertEquals(1, graph.spentTickets(root), "root has one outgoing edge: the invite to Alice");

            graph.expire(alice);

            assertEquals(0, graph.spentTickets(root), "nobody keeps paying for a player who timed out");
        }
    }

    @Nested
    @DisplayName("anti-flap, which the clock depends on")
    class AntiFlap {
        @Test
        @DisplayName("a sponsor-then-withdraw farm is blocked by the minimum duration")
        void farmIsBlocked() {
            long tenMinutes = 10L * 60_000L;
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();

            // Somebody rescues Alice, resetting her clock to full...
            graph.sponsor(root, alice, NOW, UNLIMITED);
            recompute();
            assertFalse(graph.get(alice).orElseThrow().hasGraceClock());

            // ...and immediately tries to take it back so they can mint another full window on demand.
            SponsorGraphException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    SponsorGraphException.class,
                    () -> graph.withdraw(root, alice, NOW + 1000L, tenMinutes, false));

            assertEquals(SponsorGraphException.Reason.SPONSORSHIP_TOO_YOUNG, refused.getReason());
            assertEquals(SponsorStatus.ACTIVE, graph.get(alice).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("the same withdrawal is allowed once the sponsorship has aged")
        void allowedAfterMinimum() {
            long tenMinutes = 10L * 60_000L;
            graph.withdraw(root, alice, NOW, NO_MIN_AGE, false);
            recompute();
            graph.sponsor(root, alice, NOW, UNLIMITED);
            recompute();

            graph.withdraw(root, alice, NOW + tenMinutes + 1L, tenMinutes, false);
            recompute();

            assertEquals(SponsorStatus.ABANDONED, graph.get(alice).orElseThrow().getStatus());
        }
    }
}
