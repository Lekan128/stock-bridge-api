package com.procurepal_services.stock_bridge_api.selling;

/**
 * How the customer paid for a sale at the counter (SELLING_TILL_PLAN.md Step 1). CARD is the POS
 * machine. Not the marketplace's {@code PaymentMethod}, which is about orders paid online.
 */
public enum SalePaymentMethod {
    CASH,
    TRANSFER,
    CARD,
    /** A table's bill paid in parts, more than one way (V101, Step 11) - its parts are the bill's payments. */
    SPLIT
}
