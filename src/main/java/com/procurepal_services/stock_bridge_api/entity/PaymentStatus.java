package com.procurepal_services.stock_bridge_api.entity;

/**
 * The money axis of an order (orders.payment_status). Distinct from
 * {@link PaymentProviderStatus}, which is the status of one individual attempt
 * against the payment provider: an order can be PENDING while three separate
 * Monnify attempts sit at ABANDONED, FAILED and PENDING.
 */
public enum PaymentStatus {

    /** A Monnify checkout is outstanding. */
    PENDING,
    PAID,
    FAILED,
    /** Pay-on-delivery: money is owed and will be collected by the rider. */
    ON_DELIVERY,
    REFUNDED;

    public boolean isSettled() {
        return this == PAID || this == REFUNDED;
    }
}
