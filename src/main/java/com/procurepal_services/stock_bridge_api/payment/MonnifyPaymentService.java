package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Payment;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentApplication;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentContext;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentResponse;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitCommand;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitResult;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.payment.dto.PaymentVerificationResponse;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.PaymentRepository;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buyer-facing half of Monnify: opening a checkout, and re-verifying when the
 * browser comes back. Neither operation ever concludes anything from the request
 * itself - {@link PaymentApplicationService} decides, from a status this server
 * fetched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonnifyPaymentService {

    private static final String PROVIDER = "MONNIFY";
    private static final String DEFAULT_CURRENCY = "NGN";

    private final MonnifyClient monnifyClient;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentApplicationService paymentApplicationService;
    private final OrderPaymentApplication orderPaymentApplication;

    // ------------------------------------------------------------------------
    // Initialize
    // ------------------------------------------------------------------------

    /**
     * Opens a fresh Monnify checkout for an order the caller owns.
     *
     * <h2>Retry always means a new attempt</h2>
     * A stored {@code checkoutUrl} is never re-served. Monnify expires it 40
     * minutes after issue, and a buyer sent back to a dead URL lands on a provider
     * error page with no route forward. Every call therefore mints a NEW
     * paymentReference and a NEW Payment row, which also keeps the evidence trail
     * intact: an abandoned first attempt stays visible instead of being overwritten
     * by the retry.
     *
     * <h2>Transaction boundary</h2>
     * The provider call sits inside the transaction on purpose. If Monnify fails,
     * the Payment row rolls back with it and no orphan PENDING attempt is left for
     * the sweep to chase. The residual risk is a timeout where Monnify did create
     * the transaction: we discard our row, the buyer never receives that
     * checkoutUrl, and their retry gets a brand-new reference - so the orphan on
     * Monnify's side is simply never paid. That is strictly better than the
     * alternative failure, which is a live checkout URL we have no record of.
     */
    @Transactional
    public InitializePaymentResponse initialize(UUID orderId, UUID callerClientId) {
        if (!monnifyClient.isConfigured()) {
            log.warn("Checkout attempted for order {} while Monnify is unconfigured", orderId);
            throw new MonnifyNotConfiguredException();
        }

        // Ownership first, and via an explicit client_id predicate rather than the
        // Hibernate tenant filter - §6 of the contract, and the difference between
        // "another company cannot see this" and "another company probably cannot
        // see this".
        Order order = orderRepository
                .findByIdAndClientId(orderId, callerClientId)
                .orElseThrow(PaymentNotFoundException::new);

        assertPayable(order);

        OrderPaymentContext context = orderPaymentApplication.loadPaymentContext(orderId);

        String paymentReference = nextPaymentReference(order.getOrderNumber());
        String currency = context.currency() != null ? context.currency() : DEFAULT_CURRENCY;

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .provider(PROVIDER)
                .paymentReference(paymentReference)
                .status(PaymentProviderStatus.PENDING)
                .amount(context.total())
                .currency(currency)
                .build());

        MonnifyInitResult result = monnifyClient.initializeTransaction(new MonnifyInitCommand(
                paymentReference,
                context.total(),
                currency,
                // Monnify shows these on its hosted page. Fall back rather than send
                // null - an init rejected for a missing customer name is a checkout
                // the buyer cannot complete.
                blankToDefault(context.customerName(), "ProcurePal customer"),
                blankToDefault(context.customerEmail(), "orders@procurepal.ng"),
                "ProcurePal order " + order.getOrderNumber()));

        payment.setTransactionReference(result.transactionReference());
        payment.setCheckoutUrl(result.checkoutUrl());
        paymentRepository.save(payment);

        log.info("Opened Monnify checkout paymentReference={} transactionReference={} for order={} client={}",
                paymentReference, result.transactionReference(), order.getOrderNumber(), callerClientId);

        return new InitializePaymentResponse(
                result.checkoutUrl(), paymentReference, result.transactionReference());
    }

    private void assertPayable(Order order) {
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            // The double-click guard. Without it a second checkout opens against a
            // settled order and the buyer pays twice for goods they already own.
            throw new OrderNotPayableException("This order has already been paid for.");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderNotPayableException("This order has been cancelled and can no longer be paid for.");
        }
        if (order.getPaymentMethod() == PaymentMethod.PAY_ON_DELIVERY) {
            throw new OrderNotPayableException(
                    "This is a pay-on-delivery order. It will be settled when the goods arrive.");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new OrderNotPayableException("This order is no longer awaiting payment.");
        }
    }

    /**
     * Unique per ATTEMPT, not per order - Monnify rejects a paymentReference it has
     * seen before from the same merchant, so reusing the order number on a retry
     * fails the second checkout outright. The order number stays as the prefix
     * because that is what makes a Monnify dashboard row traceable back to an order
     * without a database lookup.
     *
     * <p>Bounded well under the column's 100 characters, and random rather than a
     * counter so two concurrent retries cannot collide - and if they somehow did,
     * {@code uq_payments_payment_reference} is the real guard.
     */
    private String nextPaymentReference(String orderNumber) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return orderNumber + "-" + suffix;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------------
    // Return-verify
    // ------------------------------------------------------------------------

    /**
     * Re-verifies an attempt against Monnify on behalf of the buyer's return page.
     *
     * <p>The redirect that brought them here proves nothing - it is a URL the buyer
     * controls, and its query string is trivially forged. All it does is tell us
     * WHICH reference to go and ask Monnify about.
     *
     * <p>Not {@code @Transactional}: the provider call must not run inside a
     * transaction holding the payment row lock. The mutation happens in
     * {@link PaymentApplicationService#apply}, which opens its own.
     */
    public PaymentVerificationResponse verifyForBuyer(String paymentReference, UUID callerClientId) {
        Payment payment = paymentRepository
                .findByPaymentReference(paymentReference)
                .orElseThrow(PaymentNotFoundException::new);

        // Cross-tenant guard. payments has no client_id (the webhook arrives with no
        // tenant context), so there is NO Hibernate filter protecting this read -
        // the check has to be explicit, and it has to be here. A foreign reference
        // gets the same 404 as a nonexistent one so this cannot be used to probe
        // whether a reference is real.
        Order order = payment.getOrder();
        if (order == null || !callerClientId.equals(order.getClientId())) {
            log.warn("Client {} attempted to verify paymentReference={} belonging to another company",
                    callerClientId, paymentReference);
            throw new PaymentNotFoundException();
        }

        if (payment.getStatus().isFinal()) {
            // Already settled - by the webhook, or by an earlier refresh of this same
            // page. Answering from our own record avoids a pointless provider round
            // trip on every refresh, and is safe precisely because a final status is
            // one this server already verified.
            return describe(payment, order, "Payment already verified.");
        }

        if (payment.getTransactionReference() == null) {
            return describe(payment, order, "This payment was never started with the provider.");
        }

        if (!monnifyClient.isConfigured()) {
            // Cannot verify, so do not guess. The attempt stays PENDING and the sweep
            // picks it up once credentials are restored.
            log.error("Cannot verify paymentReference={} - Monnify is unconfigured", paymentReference);
            throw new MonnifyNotConfiguredException();
        }

        MonnifyTransactionStatus status = monnifyClient.getTransactionStatus(payment.getTransactionReference());
        PaymentApplicationOutcome outcome =
                paymentApplicationService.apply(paymentReference, status, PaymentVerificationSource.RETURN_VERIFY);

        // Re-read: apply() committed in its own transaction, and the instance above
        // predates that write.
        Payment refreshed = paymentRepository.findByPaymentReference(paymentReference).orElseThrow();
        return describe(refreshed, refreshed.getOrder(), messageFor(outcome));
    }

    private static String messageFor(PaymentApplicationOutcome outcome) {
        return switch (outcome) {
            case APPLIED_PAID, ALREADY_FINAL -> "Payment confirmed.";
            case UNDERPAID -> "The amount received was less than the order total. This order has not been paid.";
            case APPLIED_FAILED -> "The payment did not go through. You can try again.";
            case STILL_PENDING, UNVERIFIABLE_AMOUNT -> "Payment is still being confirmed. Please wait a moment.";
            case UNKNOWN_REFERENCE -> "Payment not found.";
        };
    }

    private PaymentVerificationResponse describe(Payment payment, Order order, String message) {
        return new PaymentVerificationResponse(
                payment.getPaymentReference(),
                payment.getTransactionReference(),
                payment.getStatus(),
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                payment.getAmount(),
                payment.getAmountPaid(),
                payment.getPaidAt(),
                message);
    }
}
