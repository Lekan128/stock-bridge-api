package com.procurepal_services.stock_bridge_api.entity;

import java.util.Set;

/**
 * The fulfilment axis of an order's life. Money lives on the separate
 * {@link PaymentStatus} axis - see the orders table comment in
 * V6__marketplace.sql for why the two are not collapsed into one column.
 *
 * The allowed transitions are declared here rather than in a service so there
 * is exactly one definition of the state machine. Every module that advances an
 * order must go through {@link #canTransitionTo(OrderStatus)}; a second,
 * hand-written copy of these rules in a controller is how state machines rot.
 */
public enum OrderStatus {

    /** Monnify checkout created, nothing paid yet. Auto-cancelled after 24h. */
    PENDING_PAYMENT,
    /** Paid (or accepted as pay-on-delivery). This is the point at which the buyer's incoming stock appears. */
    PLACED,
    CONFIRMED,
    PROCESSING,
    OUT_FOR_DELIVERY,
    /** ProcurePal says it arrived. Only the BUYER can move it past here. */
    DELIVERED,
    /** The buyer signed for it; incoming stock has become real on-hand stock. */
    RECEIVED,
    CANCELLED;

    /**
     * Whether this status is one the platform owner drives. DELIVERED ->
     * RECEIVED is the one transition ProcurePal must never make on the buyer's
     * behalf: it is the buyer asserting the goods are in their store, and it
     * writes stock into their inventory.
     */
    public boolean isBuyerDriven() {
        return this == DELIVERED;
    }

    public boolean isTerminal() {
        return this == RECEIVED || this == CANCELLED;
    }

    public Set<OrderStatus> allowedNextStatuses() {
        return switch (this) {
            case PENDING_PAYMENT -> Set.of(PLACED, CANCELLED);
            case PLACED -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(PROCESSING, CANCELLED);
            case PROCESSING -> Set.of(OUT_FOR_DELIVERY, CANCELLED);
            // No CANCELLED from here on: once it is on a bike it is delivered or
            // returned, and a return is a refund conversation, not a cancellation.
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(RECEIVED);
            case RECEIVED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNextStatuses().contains(next);
    }
}
