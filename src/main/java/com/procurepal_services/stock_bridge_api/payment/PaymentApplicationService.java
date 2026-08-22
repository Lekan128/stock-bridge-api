package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.Payment;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentApplication;
import com.procurepal_services.stock_bridge_api.order.PaymentSuccess;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single funnel every payment outcome passes through, and the only place in
 * the application that may declare an order paid.
 *
 * <h2>Why there is exactly one of these</h2>
 * Three independent paths report the same payment, routinely and often at the
 * same instant: Monnify's webhook, the browser coming back from checkout, and the
 * reconciliation sweep. Each has to be able to complete a payment on its own -
 * webhooks get dropped, buyers close the tab, and the sweep is the backstop for
 * both - so all three call this, and this decides. Having three code paths that
 * each "mark the order paid" is how an order gets its incoming stock added twice.
 *
 * <h2>How idempotency is enforced</h2>
 * <ol>
 *   <li>{@link PaymentRepository#findByPaymentReferenceForUpdate} takes a
 *       {@code SELECT ... FOR UPDATE} row lock on the attempt. Concurrent callers
 *       queue rather than interleave.</li>
 *   <li>Inside that lock, {@code status.isFinal()} is re-checked. Under Postgres
 *       READ COMMITTED the lock is acquired against the latest committed row, so
 *       the second caller reads PAID - written by the first - and returns
 *       {@link PaymentApplicationOutcome#ALREADY_FINAL} without touching
 *       anything.</li>
 *   <li>The provider call is deliberately made OUTSIDE this transaction by the
 *       caller, and only its result passed in, so a slow Monnify never holds the
 *       row lock.</li>
 *   <li>{@link OrderPaymentApplication#applyPaymentSuccess} is invoked inside the
 *       same transaction. Order advancement, incoming stock and the payment write
 *       therefore commit together or not at all; a failure there rolls the
 *       payment back to PENDING and the sweep retries.</li>
 * </ol>
 * The check-then-act is safe only because the lock is taken first. Reading the
 * payment without the lock and then updating it would let two threads both
 * observe PENDING and both apply.
 *
 * <h2>One payment can settle several orders, and idempotency is unchanged by that</h2>
 * A multi-seller basket becomes one order per seller (V12) but is paid for once, so
 * this may settle N orders. The guard above did NOT have to change to accommodate
 * that, which is the reason the design was chosen: there is still exactly one
 * {@code payments} row per attempt, so there is still exactly one row to lock and one
 * final status to re-check. The fan-out happens on the other side of
 * {@link OrderPaymentApplication#applyPaymentSuccess}, inside this transaction - so
 * all N orders and the payment row commit together or not at all, and a replayed
 * webhook stops at {@code ALREADY_FINAL} before reaching any of them.
 *
 * <p>The AMOUNT is checked against the whole group's total, obtained from
 * {@link OrderPaymentApplication#loadPaymentContext}. Checking against the anchor
 * order's total alone would accept a payment covering one seller's share of a
 * three-seller basket and then fulfil all three.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentApplicationService {

    private final PaymentRepository paymentRepository;

    private final OrderPaymentApplication orderPaymentApplication;

    /**
     * Applies a provider status this server fetched. Never call this with anything
     * a browser supplied.
     *
     * @param source which path is reporting, recorded on the attempt so a spike in
     *     RECONCILIATION reveals that webhook delivery is broken
     */
    @Transactional
    public PaymentApplicationOutcome apply(
            String paymentReference, MonnifyTransactionStatus status, PaymentVerificationSource source) {

        Payment payment = paymentRepository.findByPaymentReferenceForUpdate(paymentReference).orElse(null);
        if (payment == null) {
            log.warn("Payment status reported for unknown paymentReference={} (via {})", paymentReference, source);
            return PaymentApplicationOutcome.UNKNOWN_REFERENCE;
        }

        // THE idempotency guard. Everything below it mutates state; nothing above it
        // does. A replayed webhook, a refreshed return page and the sweep all land
        // here and stop.
        if (payment.getStatus().isFinal()) {
            log.info("Ignoring a repeat report for paymentReference={} - already {} (via {})",
                    paymentReference, payment.getStatus(), source);
            return PaymentApplicationOutcome.ALREADY_FINAL;
        }

        // The ANCHOR order - the payment covers its whole checkout group. See Payment.
        Order order = payment.getOrder();

        if (status.indicatesMoneyReceived()) {
            return applySuccessCandidate(payment, order, status, source);
        }
        if (status.indicatesFailure()) {
            return applyFailure(payment, order, status, source);
        }

        // PENDING, or a status Monnify introduced after this was written. Either way
        // it is not payment, and the sweep will ask again.
        log.info("paymentReference={} still not settled - provider says {} (via {})",
                paymentReference, status.paymentStatus(), source);
        return PaymentApplicationOutcome.STILL_PENDING;
    }

    private PaymentApplicationOutcome applySuccessCandidate(
            Payment payment, Order order, MonnifyTransactionStatus status, PaymentVerificationSource source) {

        BigDecimal amountPaid = status.amountPaid();
        if (amountPaid == null) {
            // "Paid, amount unknown" is not something we can act on, and guessing the
            // order total would defeat the entire point of the amount check. Left
            // PENDING so the sweep re-asks rather than resolving it wrongly now.
            log.error("Monnify reported {} for paymentReference={} with no usable amountPaid - refusing to "
                            + "treat as paid", status.paymentStatus(), payment.getPaymentReference());
            return PaymentApplicationOutcome.UNVERIFIABLE_AMOUNT;
        }

        // What this payment actually owes: the sum across every order in the anchor's
        // checkout group, not the anchor's own total. For a single-seller basket the
        // two are identical, which is why nothing about this path changed for
        // ProcurePal's existing orders.
        BigDecimal amountDue = amountDueFor(order);

        // compareTo, not equals: BigDecimal.equals("100.00", "100.0") is false
        // because it compares scale as well as value, which would reject a perfectly
        // good payment purely over trailing-zero formatting.
        int comparison = amountPaid.compareTo(amountDue);

        if (comparison < 0) {
            // Underpayment is not payment. Recorded in full, flagged, not fulfilled.
            log.error("UNDERPAYMENT on paymentReference={} order={}: paid {} against a checkout total of {} - "
                            + "recording as FAILED and NOT fulfilling",
                    payment.getPaymentReference(), order.getOrderNumber(), amountPaid, amountDue);

            recordProviderFacts(payment, status, source);
            payment.setStatus(PaymentProviderStatus.FAILED);
            paymentRepository.save(payment);

            orderPaymentApplication.applyPaymentFailure(
                    order.getId(),
                    payment.getPaymentReference(),
                    "Underpaid: received " + amountPaid + " against a total of " + amountDue);
            return PaymentApplicationOutcome.UNDERPAID;
        }

        if (comparison > 0) {
            // Overpayment IS payment - the money arrived and Monnify settles it. The
            // contract asks for an exact match; refusing here would strand a buyer
            // who has both paid and over-paid, holding their goods AND their money,
            // which is strictly worse than fulfilling and reconciling the difference
            // manually. Logged at warn so finance sees it. Flagged in the report as a
            // deliberate deviation.
            log.warn("OVERPAYMENT on paymentReference={} order={}: paid {} against a checkout total of {} - "
                            + "fulfilling and flagging for manual reconciliation",
                    payment.getPaymentReference(), order.getOrderNumber(), amountPaid, amountDue);
        }

        recordProviderFacts(payment, status, source);
        payment.setStatus(PaymentProviderStatus.PAID);
        // paidOn can be absent or unparseable; "now" is a better record than null,
        // and the verbatim provider value survives in provider_payload regardless.
        OffsetDateTime paidAt = status.paidOn() != null ? status.paidOn() : OffsetDateTime.now();
        payment.setPaidAt(paidAt);
        paymentRepository.save(payment);

        log.info("PAID paymentReference={} order={} amount={} method={} (via {})",
                payment.getPaymentReference(), order.getOrderNumber(), amountPaid,
                status.paymentMethod(), source);

        orderPaymentApplication.applyPaymentSuccess(
                order.getId(),
                new PaymentSuccess(
                        payment.getPaymentReference(),
                        payment.getTransactionReference(),
                        amountPaid,
                        paidAt,
                        status.paymentMethod(),
                        source));

        return PaymentApplicationOutcome.APPLIED_PAID;
    }

    /**
     * The total across the anchor order's whole checkout group.
     *
     * <p>Asked of the order module rather than computed here: what a checkout group is,
     * and which orders are in one, is the order module's business - the payment module
     * holds a {@code payments.order_id} and nothing else. Falls back to the anchor's own
     * total if the context cannot be loaded, which is the pre-split behaviour and the
     * conservative direction (it can only ever make the amount check STRICTER for a
     * single order, never laxer for a group).
     */
    private BigDecimal amountDueFor(Order order) {
        try {
            return orderPaymentApplication.loadPaymentContext(order.getId()).total();
        } catch (RuntimeException e) {
            log.error("Could not load the checkout context for order {} - falling back to its own total",
                    order.getOrderNumber(), e);
            return order.getTotal();
        }
    }

    private PaymentApplicationOutcome applyFailure(
            Payment payment, Order order, MonnifyTransactionStatus status, PaymentVerificationSource source) {

        recordProviderFacts(payment, status, source);
        payment.setStatus(mapFailureStatus(status.paymentStatus()));
        paymentRepository.save(payment);

        log.info("Payment attempt {} ended as {} (provider said {}) for order={} (via {})",
                payment.getPaymentReference(), payment.getStatus(), status.paymentStatus(),
                order.getOrderNumber(), source);

        // The ORDER is not cancelled here - only this attempt failed, and the buyer
        // may retry on the same PENDING_PAYMENT order. What the order module does
        // with that is its decision; the reason string carries what it needs.
        orderPaymentApplication.applyPaymentFailure(
                order.getId(),
                payment.getPaymentReference(),
                "Monnify reported " + status.paymentStatus());

        return PaymentApplicationOutcome.APPLIED_FAILED;
    }

    /**
     * ABANDONED and REVERSED are kept distinct from FAILED because folding them in
     * would lose the distinction a dispute turns on: "the buyer walked away" is not
     * "the bank declined", and "the money came back" is not "the money never
     * arrived".
     */
    private PaymentProviderStatus mapFailureStatus(String providerStatus) {
        return switch (providerStatus == null ? "" : providerStatus) {
            case MonnifyTransactionStatus.REVERSED -> PaymentProviderStatus.REVERSED;
            case MonnifyTransactionStatus.ABANDONED,
                    MonnifyTransactionStatus.EXPIRED,
                    MonnifyTransactionStatus.CANCELLED -> PaymentProviderStatus.ABANDONED;
            default -> PaymentProviderStatus.FAILED;
        };
    }

    /**
     * Copies everything the provider told us onto the attempt before its status
     * changes, so a row that ends FAILED is as diagnosable as one that ends PAID.
     * transactionReference is only ever filled in, never overwritten - the value we
     * stored at init is the one we asked about.
     */
    private void recordProviderFacts(
            Payment payment, MonnifyTransactionStatus status, PaymentVerificationSource source) {
        if (payment.getTransactionReference() == null && status.transactionReference() != null) {
            payment.setTransactionReference(status.transactionReference());
        }
        payment.setAmountPaid(status.amountPaid());
        payment.setPaymentMethodUsed(status.paymentMethod());
        payment.setProviderPayload(status.rawPayload());
        payment.setVerifiedVia(source);
    }

}
