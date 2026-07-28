package com.procurepal_services.stock_bridge_api.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The provider's verdict on one transaction, fetched by this server from
 * {@code GET /api/v2/transactions/{transactionReference}}. This - not a webhook
 * body, and certainly not a query parameter - is the only thing a payment is ever
 * applied from.
 *
 * @param paymentStatus Monnify's raw string, one of PAID, OVERPAID,
 *     PARTIALLY_PAID, PENDING, ABANDONED, CANCELLED, FAILED, REVERSED, EXPIRED.
 *     Kept as a String rather than parsed into an enum on purpose: an unknown
 *     status the provider adds later must be recorded and treated as
 *     "not yet paid", not blow up deserialization mid-callback.
 * @param amountPaid what was actually received. Monnify sends this as a JSON
 *     <em>string</em> ("100.00"); BigDecimal is required so the amount check is
 *     exact - a double would make 99.99 vs 100.00 a coin toss.
 * @param rawPayload the verbatim {@code responseBody} JSON, persisted to
 *     {@code payments.provider_payload} for dispute forensics, including fields
 *     we do not model.
 */
public record MonnifyTransactionStatus(
        String transactionReference,
        String paymentReference,
        BigDecimal amountPaid,
        BigDecimal totalPayable,
        String paymentStatus,
        String paymentMethod,
        OffsetDateTime paidOn,
        String rawPayload) {

    public static final String PAID = "PAID";
    public static final String OVERPAID = "OVERPAID";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PENDING = "PENDING";
    public static final String ABANDONED = "ABANDONED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";
    public static final String REVERSED = "REVERSED";
    public static final String EXPIRED = "EXPIRED";

    /**
     * Monnify settles an OVERPAID transaction as successfully paid, so both count
     * as "the money arrived" - the amount check downstream decides whether that
     * money is enough. PARTIALLY_PAID is excluded here rather than being caught by
     * the amount check alone, so an underpayment is refused even if a future
     * Monnify change made the amounts appear to line up.
     */
    public boolean indicatesMoneyReceived() {
        return PAID.equals(paymentStatus) || OVERPAID.equals(paymentStatus);
    }

    /** Terminal without money. The buyer may still retry payment on the same order. */
    public boolean indicatesFailure() {
        return PARTIALLY_PAID.equals(paymentStatus)
                || ABANDONED.equals(paymentStatus)
                || CANCELLED.equals(paymentStatus)
                || FAILED.equals(paymentStatus)
                || EXPIRED.equals(paymentStatus)
                || REVERSED.equals(paymentStatus);
    }
}
