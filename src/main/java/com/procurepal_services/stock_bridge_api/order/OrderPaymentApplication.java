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
 *
 * <h2>ONE PAYMENT, N ORDERS</h2>
 * Since checkout began splitting a multi-seller basket into one order per seller
 * (V12), the unit a payment settles is the CHECKOUT GROUP, not the order. The
 * signatures below still take a {@code UUID orderId} - deliberately, because the
 * payment module holds a {@code payments.order_id} and should not have to learn
 * what a checkout group is - but their CONTRACT changed: the named order is an
 * ANCHOR, and an implementation must apply the outcome to EVERY order in that
 * order's checkout group, atomically, inside the caller's transaction.
 *
 * <p>For the ordinary single-seller checkout the group is that one order and the
 * behaviour is byte-for-byte what it was, which is why no caller had to change.
 * For a split basket, settling only the anchor would leave the buyer having paid
 * for three orders and received one - the worst outcome this feature can produce,
 * and the reason the fan-out lives behind this interface rather than in the
 * payment module where it would have to be repeated on the success, failure and
 * reconciliation paths.
 */
public interface OrderPaymentApplication {

    /**
     * Money confirmed. Move the order(s) to PLACED/PAID, materialise incoming stock,
     * write the status events and notify. Called exactly once per successful payment
     * attempt.
     *
     * @param anchorOrderId any order in the checkout group being settled - in practice
     *     the one the {@code payments} row points at. The implementation settles the
     *     WHOLE group; see the ONE PAYMENT, N ORDERS section above.
     * @param success carries {@code amountPaid} for the ENTIRE group. An implementation
     *     must not compare it against a single order's total.
     */
    void applyPaymentSuccess(UUID anchorOrderId, PaymentSuccess success);

    /**
     * The attempt did not result in money: declined, abandoned, expired, reversed,
     * underpaid, or swept up as a stale checkout past its 24-hour grace period.
     *
     * <p>The order does NOT become CANCELLED merely because one attempt failed -
     * a buyer may retry payment on the same PENDING_PAYMENT order. {@code reason}
     * carries the distinction the implementation needs to decide.
     *
     * @param anchorOrderId any order in the checkout group; the whole group is
     *     notified, for the same reason the success path settles the whole group.
     * @param paymentReference our reference for the failed attempt, or {@code null}
     *     when there was no attempt at all (an order abandoned before checkout was
     *     ever initialized).
     */
    void applyPaymentFailure(UUID anchorOrderId, String paymentReference, String reason);

    /**
     * Everything the payment module needs to open a Monnify checkout, without giving
     * it a reason to query {@code orders} itself.
     *
     * <p>Returns the whole CHECKOUT GROUP the named order belongs to: its member ids
     * and, critically, its summed {@code total}. That total is what gets charged and
     * what the amount check runs against, so an implementation that returned only the
     * anchor order's total would under-charge a split basket by the value of every
     * other seller's goods.
     *
     * @throws RuntimeException (implementation-specific, mapped by the order
     *     module's advice) if no such order exists
     */
    OrderPaymentContext loadPaymentContext(UUID anchorOrderId);
}
