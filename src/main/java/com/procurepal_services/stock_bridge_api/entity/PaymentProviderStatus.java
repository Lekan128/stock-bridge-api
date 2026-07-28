package com.procurepal_services.stock_bridge_api.entity;

/**
 * The state of one payment ATTEMPT against the provider (payments.status), as
 * opposed to the order-level {@link PaymentStatus}. One order can have several
 * of these; only a transition into PAID may ever mark the order paid.
 *
 * ABANDONED and REVERSED exist because Monnify reports them and silently
 * folding either into FAILED would lose the distinction that matters in a
 * dispute: "the buyer walked away" is not "the bank declined", and "the money
 * came back" is not "the money never arrived".
 */
public enum PaymentProviderStatus {

    PENDING,
    PAID,
    FAILED,
    ABANDONED,
    REVERSED;

    /**
     * A PAID attempt is final; re-applying a success (webhook, then the browser
     * return, then the reconciliation sweep - all three can report the same
     * payment) must be a no-op, and this is the check that makes it one.
     */
    public boolean isFinal() {
        return this != PENDING;
    }
}
