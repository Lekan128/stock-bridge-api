package com.procurepal_services.stock_bridge_api.purchase.dto;

/**
 * Which of the two ledgers a {@link PurchaseHistoryEntry} came from.
 *
 * <p>A company's purchase history is not one table - a marketplace order and a manual stock-in
 * are two genuinely different records of "we bought this from that supplier", and this is what
 * lets the screen filter to one or the other instead of always showing both interleaved.
 */
public enum PurchaseSource {
    /** A placed {@code Order} against a seller with a marketplace account. */
    MARKETPLACE_ORDER,
    /** An IN {@code StockMovement} entered by hand against a company vendor, on or off platform. */
    MANUAL_STOCK_IN
}
