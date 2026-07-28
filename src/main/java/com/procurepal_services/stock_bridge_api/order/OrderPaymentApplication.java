package com.procurepal_services.stock_bridge_api.order;

import java.util.UUID;

/**
 * The seam between the payment module and the order module, and the only way
 * payment code is allowed to touch an order.
 *
 * <h2>Division of ownership</h2>
 * The payment package owns {@code payments}, {@code payment_webhook_events}, the
 * Monnify HTTP client, signature verification, the amount check and
 * reconciliation. It owns nothing about fulfilment. Everything on the other side
 * of this interface - the order status transition, find-or-create of the buyer's
 * product rows, {@code incoming_quantity}, status events and notifications -
 * belongs to the order module, which implements this.
 *
 * {@code applyPaymentSuccess} is called ONLY after the payment has been verified
 * against a provider response the server itself fetched AND the amount has been
 * checked against the order total. An implementation may treat the call as
 * authoritative proof of payment; it must not re-verify, and it must not assume
 * the caller is a browser.
 *
 * <h2>Two things an implementation must handle</h2>
 * <ol>
 *   <li><b>No tenant context.</b> The Monnify webhook and the reconciliation
 *       sweep both arrive with {@link com.procurepal_services.stock_bridge_api.tenant.TenantContext}
 *       empty and the Hibernate tenant filter disabled - there is no
 *       authenticated principal on either path. An implementation that mutates a
 *       {@code TenantAwareEntity} (the buyer's Product rows, most obviously) must
 *       set the tenant context from {@code order.clientId} itself; {@code @PrePersist}
 *       on TenantAwareEntity throws outright without it.</li>
 *   <li><b>Idempotency is shared.</b> The payment side already guarantees these
 *       methods fire at most once per successful payment, by row-locking the
 *       payments row and refusing to re-apply a final one
 *       (see {@code PaymentApplicationService}). That guard is not a licence to
 *       be careless: an implementation should still be safe to re-enter, because
 *       the pay-on-delivery path reaches the same order transitions without
 *       passing through here at all.</li>
 * </ol>
 *
 * <p>Both methods are invoked inside the payment module's transaction. Throwing
 * rolls the payment write back with them, which is deliberate: an order that
 * could not be advanced must not be left recorded as paid, and the
 * reconciliation sweep will retry it.
 */
public interface OrderPaymentApplication {

    /**
     * Money confirmed. Move the order to PLACED/PAID, materialise incoming stock,
     * write the status event and notify. Called exactly once per successful
     * payment attempt.
     */
    void applyPaymentSuccess(UUID orderId, PaymentSuccess success);

    /**
     * The attempt did not result in money: declined, abandoned, expired, reversed,
     * underpaid, or swept up as a stale checkout past its 24-hour grace period.
     *
     * <p>The order does NOT become CANCELLED merely because one attempt failed -
     * a buyer may retry payment on the same PENDING_PAYMENT order. {@code reason}
     * carries the distinction the implementation needs to decide.
     *
     * @param paymentReference our reference for the failed attempt, or {@code null}
     *     when there was no attempt at all (an order abandoned before checkout was
     *     ever initialized).
     */
    void applyPaymentFailure(UUID orderId, String paymentReference, String reason);

    /**
     * Everything the payment module needs to open a Monnify checkout for an order,
     * without giving it a reason to query {@code orders} itself.
     *
     * @throws RuntimeException (implementation-specific, mapped by the order
     *     module's advice) if no such order exists
     */
    OrderPaymentContext loadPaymentContext(UUID orderId);
}
