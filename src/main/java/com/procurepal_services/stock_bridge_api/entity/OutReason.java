package com.procurepal_services.stock_bridge_api.entity;

/**
 * Why a stock-out happened - {@code stock_movements.out_reason} (V46, SELLING_PRICE_PLAN.md section
 * 4.3). Optional: a null reason is "No reason given", and the server never guesses one, except that
 * a stock-out carrying a price or a sale id is a {@link #SALE}.
 */
public enum OutReason {
    /** Sold to a customer. Carries a sale id, and usually a sale amount. */
    SALE,
    /** Used in the business - cooked, built with, handed out. Never sold. */
    USED,
    DAMAGED,
    EXPIRED,
    /** Lost or stolen. */
    LOST,
    /** Sent back to the supplier - a stock-out, never a sale. */
    RETURNED_TO_SUPPLIER,
    OTHER
}
