package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a payment MEANS to an order. The payment module decides that money arrived;
 * this decides what changes because of it.
 *
 * <h2>Running without a tenant</h2>
 * Every method here can be invoked from a Monnify webhook thread: no authenticated
 * principal, so TenantContext is empty and the Hibernate tenant filter is off. That
 * shapes two decisions.
 *
 * First, the order is loaded through {@code EntityManager.find}, not through a
 * repository finder. A by-id load is the one read Hibernate does not apply
 * {@code @Filter} to, so it works identically with the filter off (webhook), on for
 * the buyer (return-verify), or on for somebody else entirely - whereas
 * {@code findByIdForCurrentTenant} would throw for want of a tenant.
 *
 * Second, everything downstream that writes a tenant-scoped row (the buyer's
 * products, the notifications) establishes the buyer's scope itself via
 * TenantScopeExecutor. Nothing here assumes a caller set one up.
 *
 * <h2>Idempotency</h2>
 * The order row is locked PESSIMISTIC_WRITE before anything is read off it, and
 * {@code paymentStatus == PAID} is the guard. A webhook delivered twice, a
 * return-verify racing that webhook, and the reconciliation sweep arriving late all
 * converge on the same order row: the first one through moves it, the rest see PAID
 * and return. Because incoming stock is only ever materialised on the
 * PENDING_PAYMENT -> PLACED transition inside that same guarded block, it cannot be
 * applied twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPaymentApplicationService implements OrderPaymentApplication {

    private final OrderLifecycleService orderLifecycleService;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void applyPaymentSuccess(UUID orderId, PaymentSuccess success) {
        Order order = lockOrder(orderId);

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            // The expected outcome of a replayed webhook, not an error worth failing
            // the caller's transaction over.
            log.info(
                    "Payment {} for order {} was already applied; ignoring the duplicate",
                    success == null ? null : success.paymentReference(),
                    order.getOrderNumber());
            return;
        }

        // Second, independent amount check. The payment module performs this too, and
        // that duplication is the point: marking an underpaid order as paid needs a
        // human to unwind, so the guard should not exist in only one module.
        BigDecimal amountPaid = success == null ? null : success.amountPaid();
        if (amountPaid == null || amountPaid.compareTo(order.getTotal()) < 0) {
            throw new PaymentAmountMismatchException(order.getOrderNumber(), order.getTotal(), amountPaid);
        }

        order.setPaymentStatus(PaymentStatus.PAID);

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            if (success.paidAt() != null) {
                order.setPlacedAt(success.paidAt());
            }
            orderLifecycleService.enterPlaced(
                    order,
                    false,
                    "Payment confirmed" + (success.paymentMethodUsed() == null
                            ? ""
                            : " (" + success.paymentMethodUsed() + ")"),
                    null);
        } else {
            // A pay-on-delivery order being settled, or a late payment against an order
            // ProcurePal already advanced. Money is recorded; fulfilment is untouched,
            // and no incoming stock is created because it already exists from PLACED.
            log.info(
                    "Order {} was {} when payment {} was applied; recording payment only",
                    order.getOrderNumber(),
                    order.getStatus(),
                    success.paymentReference());
        }
        orderLifecycleService.notifyPaymentReceived(order);
    }

    @Override
    @Transactional
    public void applyPaymentFailure(UUID orderId, String paymentReference, String reason) {
        Order order = lockOrder(orderId);

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            // A failed attempt arriving after a successful one (a retry the buyer
            // abandoned, or an out-of-order webhook) must never un-pay an order.
            log.warn(
                    "Ignoring failure {} for order {}, which is already paid",
                    paymentReference,
                    order.getOrderNumber());
            return;
        }
        if (order.getStatus().isTerminal()) {
            return;
        }

        // Deliberately NOT set to PaymentStatus.FAILED. The order stays PENDING /
        // PENDING_PAYMENT so the buyer can start a fresh attempt on the same order;
        // the failed attempt is recorded on the payments row, which is where an
        // attempt belongs. The 24h sweep is what eventually cancels a dead checkout.
        orderLifecycleService.notifyPaymentFailed(order, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentContext loadPaymentContext(UUID orderId) {
        Order order = entityManager.find(Order.class, orderId);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        Client client = clientRepository.findById(order.getClientId()).orElse(null);
        User placedBy = order.getPlacedBy() == null
                ? null
                : userRepository.findById(order.getPlacedBy()).orElse(null);

        return new OrderPaymentContext(
                order.getId(),
                order.getOrderNumber(),
                order.getTotal(),
                order.getCurrency(),
                order.getStatus(),
                order.getPaymentStatus(),
                customerName(placedBy, client),
                // The buyer who pressed pay is the person Monnify should email; the
                // company's admin contact is the fallback, because an order must always
                // be reachable even when a sub-user has no email on file.
                firstNonBlank(placedBy == null ? null : placedBy.getEmail(),
                        client == null ? null : client.getAdminContactEmail()),
                firstNonBlank(placedBy == null ? null : placedBy.getPhone(),
                        client == null ? null : client.getPhone()));
    }

    /**
     * Row lock before any read, so two webhook deliveries of the same payment
     * serialise here rather than both seeing PENDING and both applying. Also a by-id
     * load, so it works with no tenant context - see the class comment.
     */
    private Order lockOrder(UUID orderId) {
        Order order = entityManager.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        return order;
    }

    private static String customerName(User placedBy, Client client) {
        if (placedBy != null) {
            String full = ((placedBy.getFirstName() == null ? "" : placedBy.getFirstName()) + " "
                            + (placedBy.getLastName() == null ? "" : placedBy.getLastName()))
                    .trim();
            if (!full.isBlank()) {
                return full;
            }
            if (placedBy.getUsername() != null && !placedBy.getUsername().isBlank()) {
                return placedBy.getUsername();
            }
        }
        return client == null ? "ProcurePal customer" : client.getName();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
