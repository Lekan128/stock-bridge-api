package com.procurepal_services.stock_bridge_api.payment;

/**
 * What one attempt to apply a verified provider status actually did. Returned
 * rather than thrown because none of these is an error at the call site: the
 * webhook, the browser return and the reconciliation sweep all race to report the
 * same payment, and "someone else already did this" is the expected case, not a
 * fault.
 *
 * <p>It is also what gets written to {@code payment_webhook_events.processing_note},
 * which is the first thing anyone debugging a stranded order reads.
 */
public enum PaymentApplicationOutcome {

    /** Money verified, amount checked, order advanced. Happens exactly once per attempt. */
    APPLIED_PAID,

    /** The attempt was already final; this call changed nothing. The idempotency guarantee, observed. */
    ALREADY_FINAL,

    /**
     * The provider says paid, but for less than the order total. Recorded as FAILED
     * with the payload retained and flagged - an underpayment is not a payment, and
     * fulfilling it would ship goods for money we did not receive.
     */
    UNDERPAID,

    /** Declined, abandoned, expired, cancelled or reversed. The buyer may still retry on the same order. */
    APPLIED_FAILED,

    /** Provider still says PENDING. Left alone for the sweep to pick up again. */
    STILL_PENDING,

    /** The reference belongs to no payment attempt we issued - a spoofed or stale callback. */
    UNKNOWN_REFERENCE,

    /**
     * The provider reported success but without a usable amount, so the amount
     * check could not run. Deliberately not treated as payment.
     */
    UNVERIFIABLE_AMOUNT
}
