package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Payment;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentApplication;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.PaymentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The backstop that makes the other two paths survivable.
 *
 * <h2>Why it exists</h2>
 * Webhooks get dropped, delayed, or land while the app is redeploying. Buyers
 * close the tab before the return redirect fires. Either failure alone leaves an
 * order that Monnify has been paid for sitting at PENDING_PAYMENT forever - the
 * worst outcome this module can produce, because the money is gone and the buyer
 * has nothing. This sweep re-asks Monnify about every attempt still PENDING past
 * its grace period, so no single dropped message can strand a paid order.
 *
 * <p>It funnels into the same {@link PaymentApplicationService#apply} as everyone
 * else, so a payment the webhook already applied is a no-op here, not a second
 * application.
 *
 * <h2>Two sweeps, not one</h2>
 * The second one cancels orders left at PENDING_PAYMENT past 24 hours. Those are
 * buyers who opened a checkout and never paid; without it they accumulate in the
 * buyer's order list and ProcurePal's queue forever.
 *
 * <h2>No tenant context</h2>
 * These run on a scheduler thread with no request, so TenantContext is empty and
 * the Hibernate tenant filter is off. Every query here is therefore deliberately
 * one that does not need scoping - {@code payments} is not tenant-scoped at all,
 * and the order sweep reads across all tenants on purpose, which is correct for a
 * platform-wide job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final MonnifyClient monnifyClient;
    private final PaymentApplicationService paymentApplicationService;
    private final MonnifyProperties properties;
    private final OrderPaymentApplication orderPaymentApplication;

    /**
     * {@code initialDelayString} matches the interval so a rolling restart does not
     * fire every instance's sweep simultaneously at boot, and so tests standing up a
     * context are never raced by it.
     */
    @Scheduled(
            fixedDelayString = "${app.monnify.reconciliation.interval:PT5M}",
            initialDelayString = "${app.monnify.reconciliation.interval:PT5M}")
    public void scheduledSweep() {
        if (!properties.reconciliation().enabled()) {
            return;
        }
        reconcilePendingPayments();
        cancelAbandonedCheckouts();
    }

    /**
     * Re-verifies every attempt still PENDING past the grace period. Public so a
     * test can drive it deterministically rather than waiting for the scheduler.
     *
     * @return how many attempts reached a final state as a result
     */
    public int reconcilePendingPayments() {
        if (!monnifyClient.isConfigured()) {
            // Nothing to ask. Attempts stay PENDING and will be swept once
            // credentials exist - which is exactly right, because concluding
            // anything without the provider would be a guess.
            return 0;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.reconciliation().pendingPaymentGrace());
        List<Payment> stale =
                paymentRepository.findAllByStatusAndCreatedAtBefore(PaymentProviderStatus.PENDING, cutoff);
        if (stale.isEmpty()) {
            return 0;
        }

        log.info("Reconciliation sweep: {} payment attempt(s) still PENDING since before {}", stale.size(), cutoff);
        int settled = 0;
        for (Payment payment : stale) {
            // One attempt's failure must never abort the sweep - the next one might
            // be the paid order that is currently stranded.
            try {
                if (reconcileOne(payment)) {
                    settled++;
                }
            } catch (Exception e) {
                log.error("Reconciliation failed for paymentReference={}: {}",
                        payment.getPaymentReference(), e.getMessage());
            }
        }
        return settled;
    }

    private boolean reconcileOne(Payment payment) {
        if (payment.getTransactionReference() == null) {
            // Never reached Monnify, so there is nothing to verify. It will be
            // cleaned up with its order by the abandoned-checkout sweep.
            return false;
        }

        MonnifyTransactionStatus status = monnifyClient.getTransactionStatus(payment.getTransactionReference());
        PaymentApplicationOutcome outcome = paymentApplicationService.apply(
                payment.getPaymentReference(), status, PaymentVerificationSource.RECONCILIATION);

        if (outcome == PaymentApplicationOutcome.APPLIED_PAID) {
            // Worth its own loud line: this is a payment the webhook and the return
            // both failed to deliver, i.e. evidence that webhook delivery is broken.
            log.warn("Reconciliation RESCUED a paid order: paymentReference={} was PENDING and Monnify says PAID",
                    payment.getPaymentReference());
        }
        return outcome != PaymentApplicationOutcome.STILL_PENDING;
    }

    /**
     * Cancels orders that have sat at PENDING_PAYMENT past the grace period.
     *
     * <p>Runs AFTER {@link #reconcilePendingPayments()} in the scheduled sweep, and
     * that ordering is deliberate: cancelling first could cancel an order whose
     * payment succeeded but whose webhook was lost, which is precisely the disaster
     * this class exists to prevent.
     *
     * @return how many orders were cancelled
     */
    public int cancelAbandonedCheckouts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.reconciliation().abandonedCheckoutGrace());
        List<Order> abandoned =
                orderRepository.findAllByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, cutoff);
        if (abandoned.isEmpty()) {
            return 0;
        }

        log.info("Abandoned-checkout sweep: cancelling {} order(s) unpaid since before {}", abandoned.size(), cutoff);
        int cancelled = 0;
        for (Order order : abandoned) {
            try {
                cancelOne(order);
                cancelled++;
            } catch (Exception e) {
                log.error("Could not cancel abandoned order {}: {}", order.getOrderNumber(), e.getMessage());
            }
        }
        return cancelled;
    }

    /**
     * Deliberately NOT annotated {@code @Transactional}. It is called from
     * {@link #cancelAbandonedCheckouts()} on this same bean, and a self-invocation
     * bypasses the Spring proxy entirely - the annotation would read as a
     * per-order transaction boundary while silently providing none, which is worse
     * than having none. The order module's {@code applyPaymentFailure} owns its own
     * boundary; the try/catch around this call is what keeps one bad order from
     * taking the batch down.
     */
    private void cancelOne(Order order) {
        // The latest attempt, if any - an order can be abandoned before checkout was
        // ever initialized, in which case there is no reference to report.
        String paymentReference = paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .findFirst()
                .map(Payment::getPaymentReference)
                .orElse(null);

        orderPaymentApplication.applyPaymentFailure(
                order.getId(),
                paymentReference,
                "Cancelled automatically: no payment received within "
                        + properties.reconciliation().abandonedCheckoutGrace().toHours() + " hours");
    }
}
