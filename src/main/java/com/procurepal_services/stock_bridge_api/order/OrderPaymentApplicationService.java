package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>The guard is PER ORDER, which is what makes it still correct once one payment
 * settles several of them. A replay re-enters the loop and finds every member already
 * PAID; a crash halfway through rolls the whole transaction back, so the next attempt
 * finds every member still PENDING. There is no state in which half a group is paid,
 * because there is no commit point in the middle.
 *
 * <h2>One payment, N orders</h2>
 * Every method here takes an ANCHOR order id and applies the outcome to that order's
 * whole checkout group - see {@link OrderPaymentApplication}. The orders are locked in
 * a deterministic order (by order number, the order the group finder returns) so two
 * concurrent settlements of overlapping groups cannot deadlock by taking the same two
 * rows in opposite directions. Groups do not in fact overlap - an order belongs to
 * exactly one - but the ordering costs nothing and removes the question.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPaymentApplicationService implements OrderPaymentApplication {

    private final OrderLifecycleService orderLifecycleService;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void applyPaymentSuccess(UUID anchorOrderId, PaymentSuccess success) {
        List<Order> group = lockCheckoutGroup(anchorOrderId);

        // ONE amount check for the whole group, against the summed total - because one
        // payment covered all of it. This is the second, independent check; the payment
        // module performs it too, and that duplication is the point: marking an
        // underpaid order as paid needs a human to unwind, so the guard should not exist
        // in only one module.
        //
        // Comparing against a single order's total here would be strictly wrong in both
        // directions: it would pass a payment that covered only one seller's share of a
        // three-seller basket, and it is the check that would have caught it.
        BigDecimal groupTotal = group.stream()
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amountPaid = success == null ? null : success.amountPaid();
        if (amountPaid == null || amountPaid.compareTo(groupTotal) < 0) {
            throw new PaymentAmountMismatchException(
                    group.getFirst().getOrderNumber(), groupTotal, amountPaid);
        }

        for (Order order : group) {
            if (order.getPaymentStatus() == PaymentStatus.PAID) {
                // The expected outcome of a replayed webhook, not an error worth failing
                // the caller's transaction over.
                log.info(
                        "Payment {} for order {} was already applied; ignoring the duplicate",
                        success.paymentReference(),
                        order.getOrderNumber());
                continue;
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
                // A pay-on-delivery order being settled, or a late payment against an
                // order the seller already advanced. Money is recorded; fulfilment is
                // untouched, and no incoming stock is created because it already exists
                // from PLACED.
                log.info(
                        "Order {} was {} when payment {} was applied; recording payment only",
                        order.getOrderNumber(),
                        order.getStatus(),
                        success.paymentReference());
            }
            orderLifecycleService.notifyPaymentReceived(order);
        }
    }

    @Override
    @Transactional
    public void applyPaymentFailure(UUID anchorOrderId, String paymentReference, String reason) {
        for (Order order : lockCheckoutGroup(anchorOrderId)) {
            if (order.getPaymentStatus() == PaymentStatus.PAID) {
                // A failed attempt arriving after a successful one (a retry the buyer
                // abandoned, or an out-of-order webhook) must never un-pay an order.
                log.warn(
                        "Ignoring failure {} for order {}, which is already paid",
                        paymentReference,
                        order.getOrderNumber());
                continue;
            }
            if (order.getStatus().isTerminal()) {
                continue;
            }

            // Deliberately NOT set to PaymentStatus.FAILED. The order stays PENDING /
            // PENDING_PAYMENT so the buyer can start a fresh attempt on the same order;
            // the failed attempt is recorded on the payments row, which is where an
            // attempt belongs. The 24h sweep is what eventually cancels a dead checkout.
            //
            // Notified per order rather than once for the group: each order is what the
            // buyer sees in their history, and "payment failed" against a basket they
            // can no longer identify is not actionable.
            orderLifecycleService.notifyPaymentFailed(order, reason);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentContext loadPaymentContext(UUID anchorOrderId) {
        Order order = entityManager.find(Order.class, anchorOrderId);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        List<Order> group = checkoutGroupOf(order);
        Client client = clientRepository.findById(order.getClientId()).orElse(null);
        User placedBy = order.getPlacedBy() == null
                ? null
                : userRepository.findById(order.getPlacedBy()).orElse(null);

        return new OrderPaymentContext(
                order.getId(),
                order.getOrderNumber(),
                order.getCheckoutGroupId(),
                group.stream().map(Order::getId).toList(),
                // The GROUP's total: what one Monnify transaction has to collect.
                group.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
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
     * Every order of the anchor's checkout group, each row-locked before anything is
     * read off it, so two webhook deliveries of the same payment serialise here rather
     * than both seeing PENDING and both applying.
     *
     * <p>The group is READ first (an unlocked by-id load of the anchor, then a query by
     * group id) and each member then LOCKED by id in the returned order. Locking by id
     * matters for the same reason it does in the single-order case: a by-id load is the
     * one read Hibernate does not apply {@code @Filter} to, so it works with no tenant
     * context on the webhook thread, with the buyer's filter on the return-verify path,
     * and with somebody else's during reconciliation - see the class comment.
     *
     * <p>Never empty: the anchor is always a member of its own group.
     */
    private List<Order> lockCheckoutGroup(UUID anchorOrderId) {
        Order anchor = entityManager.find(Order.class, anchorOrderId);
        if (anchor == null) {
            throw new OrderNotFoundException();
        }
        List<Order> locked = new ArrayList<>();
        for (Order member : checkoutGroupOf(anchor)) {
            locked.add(entityManager.find(Order.class, member.getId(), LockModeType.PESSIMISTIC_WRITE));
        }
        return locked;
    }

    /**
     * The orders one checkout produced, order-number ascending. Falls back to the
     * anchor alone if it somehow carries no group id, so a row written before V12 by a
     * path that bypassed the backfill still settles rather than throwing.
     */
    private List<Order> checkoutGroupOf(Order anchor) {
        if (anchor.getCheckoutGroupId() == null) {
            return List.of(anchor);
        }
        List<Order> group =
                orderRepository.findAllByCheckoutGroupIdOrderByOrderNumberAsc(anchor.getCheckoutGroupId());
        return group.isEmpty() ? List.of(anchor) : group;
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
