package com.fiskerz.sponsor_system.graph;

/**
 * How many people a player may still back.
 *
 * <p>Backing someone costs a ticket, and withdrawing refunds it. There is no separate accounting: a ticket is spent by
 * holding an edge, so "spent" is simply how many edges you currently hold. That is what makes a refund automatic — the
 * edge is gone, so the ticket is back — and it is also why an edge pointing at a player who has been removed stops
 * costing anything.
 *
 * <p>In the default shared mode, invites and sponsorships draw on one pool. That scarcity is the point: if backing
 * someone were free, it would mean nothing.
 *
 * <p>No Minecraft imports, so every branch is unit testable.
 */
public record TicketBudget(int spent, int limit) {
    /** A budget nobody can exhaust, used for the root and for operators. */
    public static final int UNLIMITED = -1;

    public static TicketBudget unlimited(int spent) {
        return new TicketBudget(spent, UNLIMITED);
    }

    public boolean isUnlimited() {
        return this.limit < 0;
    }

    /** How many more players this supporter may back; {@link Integer#MAX_VALUE} when unlimited. */
    public int remaining() {
        return this.isUnlimited() ? Integer.MAX_VALUE : Math.max(0, this.limit - this.spent);
    }

    public boolean canSpend() {
        return this.isUnlimited() || this.spent < this.limit;
    }

    /**
     * What to hand the graph as its spendable allowance: negative for unlimited, otherwise the number left.
     *
     * <p>The graph only distinguishes "none left" from "some left", so this collapses the whole budget to that.
     */
    public int spendable() {
        return this.isUnlimited() ? UNLIMITED : Math.max(0, this.limit - this.spent);
    }

    /**
     * Works out a supporter's budget.
     *
     * @param graph        the graph to count spent tickets in
     * @param supporter    who is spending
     * @param kind         which pool to draw from, or {@code null} in shared mode
     * @param sharedLimit  the shared pool size, used when {@code kind} is {@code null}
     * @param inviteLimit  the invite pool size, used when {@code kind} is PRIMARY
     * @param sponsorLimit the sponsorship pool size, used when {@code kind} is SPONSORSHIP
     * @param unlimited    whether this supporter is exempt entirely (the root, or an operator)
     */
    public static TicketBudget of(SupportGraph graph, java.util.UUID supporter, EdgeKind kind, int sharedLimit,
            int inviteLimit, int sponsorLimit, boolean unlimited) {
        if (kind == null) {
            int spent = graph.spentTickets(supporter);
            return unlimited ? unlimited(spent) : new TicketBudget(spent, sharedLimit);
        }
        int spent = kind == EdgeKind.PRIMARY ? graph.spentInvites(supporter) : graph.spentSponsorships(supporter);
        int limit = kind == EdgeKind.PRIMARY ? inviteLimit : sponsorLimit;
        return unlimited ? unlimited(spent) : new TicketBudget(spent, limit);
    }
}
