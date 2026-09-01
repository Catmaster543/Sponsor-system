package com.fiskerz.sponsor_system.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Ticket accounting, in both budget modes.
 */
class TicketBudgetTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int UNLIMITED = -1;

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
        this.graph.invite(this.root, this.bob, "Bob", NOW, UNLIMITED);
        this.graph.invite(this.alice, this.carol, "Carol", NOW, UNLIMITED);
    }

    private TicketBudget shared(UUID supporter, int limit) {
        return TicketBudget.of(this.graph, supporter, null, limit, 0, 0, false);
    }

    private TicketBudget split(UUID supporter, EdgeKind kind, int invites, int sponsorships) {
        return TicketBudget.of(this.graph, supporter, kind, 0, invites, sponsorships, false);
    }

    @Nested
    @DisplayName("shared pool")
    class Shared {
        @Test
        @DisplayName("invites and sponsorships draw on the same pool")
        void oneSharedPool() {
            assertEquals(2, shared(root, 10).spent());

            graph.sponsor(root, carol, NOW, UNLIMITED);

            assertEquals(3, shared(root, 10).spent(), "a sponsorship costs the same as an invite");
            assertEquals(7, shared(root, 10).remaining());
        }

        @Test
        @DisplayName("running out refuses further spending")
        void refusesWhenExhausted() {
            TicketBudget budget = shared(root, 2);

            assertFalse(budget.canSpend());
            assertEquals(0, budget.remaining());
            assertEquals(0, budget.spendable());
        }

        @Test
        @DisplayName("a withdrawal frees the ticket immediately")
        void withdrawalRefunds() {
            assertFalse(shared(root, 2).canSpend());

            graph.withdraw(root, bob, NOW, 0L, false);

            assertTrue(shared(root, 2).canSpend());
            assertEquals(1, shared(root, 2).remaining());
        }

        @Test
        @DisplayName("an over-spent pool reports zero rather than a negative number")
        void neverNegative() {
            TicketBudget budget = shared(root, 1);

            assertEquals(0, budget.remaining());
            assertEquals(0, budget.spendable());
        }
    }

    @Nested
    @DisplayName("split pools")
    class Split {
        @Test
        @DisplayName("spending an invite does not reduce the sponsorship pool")
        void poolsAreIndependent() {
            graph.sponsor(root, carol, NOW, UNLIMITED);

            assertEquals(2, split(root, EdgeKind.PRIMARY, 5, 5).spent());
            assertEquals(1, split(root, EdgeKind.SPONSORSHIP, 5, 5).spent());
            assertEquals(3, split(root, EdgeKind.PRIMARY, 5, 5).remaining());
            assertEquals(4, split(root, EdgeKind.SPONSORSHIP, 5, 5).remaining());
        }

        @Test
        @DisplayName("an exhausted invite pool leaves sponsorships available")
        void exhaustingOneLeavesTheOther() {
            assertFalse(split(root, EdgeKind.PRIMARY, 2, 5).canSpend());
            assertTrue(split(root, EdgeKind.SPONSORSHIP, 2, 5).canSpend());
        }
    }

    @Nested
    @DisplayName("exemptions")
    class Exemptions {
        @Test
        @DisplayName("an exempt supporter never runs out")
        void unlimitedNeverRunsOut() {
            TicketBudget budget = TicketBudget.of(graph, root, null, 1, 1, 1, true);

            assertTrue(budget.isUnlimited());
            assertTrue(budget.canSpend());
            assertEquals(TicketBudget.UNLIMITED, budget.spendable());
            assertEquals(2, budget.spent(), "the spend is still counted, it just does not gate anything");
        }

        @Test
        @DisplayName("a limit of -1 is unlimited for everyone")
        void negativeLimitIsUnlimited() {
            assertTrue(shared(root, -1).isUnlimited());
            assertTrue(shared(root, -1).canSpend());
        }

        @Test
        @DisplayName("a limit of zero refuses everything")
        void zeroLimitRefusesAll() {
            assertFalse(shared(alice, 0).canSpend());
        }
    }

    @Nested
    @DisplayName("refunds")
    class Refunds {
        @Test
        @DisplayName("a revoked target refunds its ticket")
        void revokedRefunds() {
            assertEquals(2, shared(root, 10).spent());

            graph.revoke(bob);

            assertEquals(1, shared(root, 10).spent());
        }

        @Test
        @DisplayName("a forgotten target refunds its ticket")
        void forgottenRefunds() {
            graph.forget(bob);

            assertEquals(1, shared(root, 10).spent());
        }

        @Test
        @DisplayName("an abandoned target still costs a ticket")
        void abandonedStillCosts() {
            // Abandonment is about the target's own support, not about whether their supporter is still paying.
            graph.withdraw(root, alice, NOW, 0L, false);
            graph.recomputeSupport(SupportModel.REACHABILITY);

            assertEquals(SponsorStatus.ABANDONED, graph.get(carol).orElseThrow().getStatus());
            assertEquals(1, shared(alice, 10).spent(), "Alice still backs Carol");
        }
    }
}
