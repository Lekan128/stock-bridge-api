package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When an unpaid order will be cancelled, so the buyer can be SHOWN the deadline
 * instead of being emailed about it afterwards.
 *
 * <p>Reads the same property the abandoned-checkout sweep in
 * {@code PaymentReconciliationService} acts on, rather than importing the payment
 * module's {@code MonnifyProperties}: the payment module depends on this one, not
 * the other way round. The sweep selects by {@code orders.created_at}, so that is
 * what the deadline is measured from.
 */
@Component
public class UnpaidOrderExpiry {

    private final Duration grace;

    public UnpaidOrderExpiry(
            @Value("${app.monnify.reconciliation.abandoned-checkout-grace:PT24H}") Duration grace) {
        this.grace = grace;
    }

    /** Null for anything not awaiting payment - there is no deadline to show. */
    public OffsetDateTime paymentDueBy(Order order) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getCreatedAt() == null) {
            return null;
        }
        return order.getCreatedAt().plus(grace);
    }
}
