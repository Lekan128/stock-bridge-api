package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where a waybill is (MULTI_BRANCH_BUILD_PLAN.md section 5.1). Mirrors stock_transfers.status's check.
 * Deliberately few: Shopify took steps OUT of its transfers because the full chain was too heavy
 * (MULTI_BRANCH_UX_PLAN.md section 9.3).
 */
public enum StockTransferStatus {
    /** Sent, nothing received yet. */
    ON_THE_WAY,
    /** Some received; the rest is still coming (a second trip). */
    PARTLY_RECEIVED,
    /** Everything sent was received. */
    RECEIVED,
    /** Everything is accounted for, and some of it was missing or damaged. */
    RECEIVED_SHORT,
    /** Taken back before anything was received: the stock is on the sending branch's shelf again. */
    CANCELLED;

    public boolean isOpen() {
        return this == ON_THE_WAY || this == PARTLY_RECEIVED;
    }
}
