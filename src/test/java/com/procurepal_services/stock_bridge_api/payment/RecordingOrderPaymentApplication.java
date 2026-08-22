package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentApplication;
import com.procurepal_services.stock_bridge_api.order.OrderPaymentContext;
import com.procurepal_services.stock_bridge_api.order.PaymentSuccess;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * A miniature of what the order module does on payment, standing in for it while
 * the two modules are built in parallel.
 *
 * <h2>Why it mutates real rows instead of just counting calls</h2>
 * "applyPaymentSuccess was invoked once" is a much weaker claim than "the order
 * reached PLACED/PAID once and incoming stock went up by exactly one lot". The
 * idempotency guarantee is about committed database state, so the double is built
 * to produce committed database state: it advances the order and increments
 * {@code products.incoming_quantity}, both of which the tests assert on directly.
 *
 * <p>It is deliberately NOT idempotent itself. Calling it twice really would
 * double the incoming quantity - which is precisely what makes the replay tests
 * meaningful. The guarantee under test belongs to
 * {@link com.procurepal_services.stock_bridge_api.payment.PaymentApplicationService},
 * and a self-guarding double would hide a failure of it.
 *
 * <p>It also demonstrates the tenant-context obligation the interface documents:
 * it loads the buyer's Product by id (find-by-id is not tenant-filtered) rather
 * than through a tenant-scoped finder, because there is no TenantContext on the
 * webhook or scheduler threads.
 *
 * <h2>Why it displaces M4's real service here</h2>
 * The order module ALSO guards idempotency, by row-locking the order and checking
 * {@code paymentStatus == PAID}. That is correct defence in depth, but it means an
 * end-to-end replay test passes even if the payment module's own guard is broken -
 * M4's would silently cover for it. {@code @Primary} puts this deliberately
 * UNGUARDED double in the way instead, so the replay tests are measuring
 * {@code PaymentApplicationService}'s lock-and-recheck and nothing else.
 * {@link MonnifyPaymentOrderLoopIntegrationTest} runs the same payment against
 * M4's real implementation to prove the two halves fit together.
 */
@Primary
@TestComponent
@RequiredArgsConstructor
public class RecordingOrderPaymentApplication implements OrderPaymentApplication {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    private final List<PaymentSuccess> successes = new CopyOnWriteArrayList<>();
    private final List<Failure> failures = new CopyOnWriteArrayList<>();

    /** Which product an order's incoming stock lands on, registered by the test fixture. */
    private final Map<UUID, UUID> productByOrder = new ConcurrentHashMap<>();

    public record Failure(UUID orderId, String paymentReference, String reason) {
    }

    public void reset() {
        successes.clear();
        failures.clear();
        productByOrder.clear();
    }

    public void register(UUID orderId, UUID productId) {
        productByOrder.put(orderId, productId);
    }

    /**
     * Scoped by reference rather than counted globally: these tests share a
     * database, and the reconciliation sweep legitimately touches other tests'
     * pending attempts.
     */
    public List<PaymentSuccess> successesFor(String paymentReference) {
        return successes.stream()
                .filter(success -> paymentReference.equals(success.paymentReference()))
                .toList();
    }

    public List<String> failuresFor(String paymentReference) {
        return failures.stream()
                .filter(failure -> paymentReference.equals(failure.paymentReference()))
                .map(Failure::reason)
                .toList();
    }

    @Override
    @Transactional
    public void applyPaymentSuccess(UUID orderId, PaymentSuccess success) {
        successes.add(success);

        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PLACED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPlacedAt(OffsetDateTime.now());
        orderRepository.save(order);

        UUID productId = productByOrder.get(orderId);
        if (productId != null) {
            // Incoming, not on-hand: paid for but not yet received, and no
            // StockMovement - nothing has physically moved.
            Product product = productRepository.findById(productId).orElseThrow();
            product.setIncomingQuantity(product.getIncomingQuantity() + 1);
            productRepository.save(product);
        }
    }

    @Override
    public void applyPaymentFailure(UUID orderId, String paymentReference, String reason) {
        // Records only. One failed attempt does not cancel the order - the buyer may
        // retry on the same PENDING_PAYMENT order, which the retry test relies on.
        failures.add(new Failure(orderId, paymentReference, reason));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentContext loadPaymentContext(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        // Mirrors OrderPaymentApplicationService: the context describes the whole
        // checkout group, not just the anchor, so a split checkout is asked for one
        // total once. A group of one - every pre-split order - collapses to the
        // previous behaviour.
        List<Order> group = order.getCheckoutGroupId() == null
                ? List.of(order)
                : orderRepository.findAllByCheckoutGroupIdOrderByOrderNumberAsc(order.getCheckoutGroupId());
        return new OrderPaymentContext(
                order.getId(),
                order.getOrderNumber(),
                order.getCheckoutGroupId(),
                group.stream().map(Order::getId).toList(),
                group.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                order.getCurrency(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getDeliveryContactName(),
                "buyer@example.com",
                order.getDeliveryContactPhone());
    }
}
