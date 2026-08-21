package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import com.procurepal_services.stock_bridge_api.marketplace.PlatformOwnerGuard;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderStatusEventRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every status change an order can undergo, in one place: the state-machine check,
 * the {@code *_at} stamp, the audit event, the incoming-stock consequence and the
 * notifications.
 *
 * <h2>Why this is not spread across the three callers</h2>
 * An order reaches PLACED from two directions (a COD checkout, and a verified
 * Monnify payment arriving on an unauthenticated webhook thread), and leaves it from
 * three (the buyer cancelling, ProcurePal cancelling, ProcurePal fulfilling). If each
 * of those wrote its own status assignment plus event plus notification, the
 * incoming-stock rule would eventually be applied on four paths and forgotten on the
 * fifth. Transitions are funnelled here so that cannot happen.
 *
 * The state machine itself lives on {@link OrderStatus} and is only consulted from
 * here - it is never re-encoded.
 */
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderStatusEventRepository orderStatusEventRepository;
    private final IncomingStockService incomingStockService;
    private final CatalogStockService catalogStockService;
    private final NotificationService notificationService;
    private final EmailNotificationService emailNotificationService;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final ClientRepository clientRepository;

    /**
     * The one moment incoming stock appears. Reached by a COD checkout (immediately,
     * because the money is promised on the doorstep) and by a verified Monnify
     * payment - never by anything else.
     *
     * @param initialPlacement true when the order was CREATED at PLACED, in which case
     *     the creation event has already been written and there is no transition to
     *     record.
     */
    @Transactional
    public void enterPlaced(Order order, boolean initialPlacement, String note, UUID actingUserId) {
        OrderStatus previous = order.getStatus();
        if (!initialPlacement) {
            requireTransition(previous, OrderStatus.PLACED);
            order.setStatus(OrderStatus.PLACED);
            recordEvent(order, previous, OrderStatus.PLACED, note, actingUserId);
        }
        if (order.getPlacedAt() == null) {
            order.setPlacedAt(OffsetDateTime.now());
        }

        incomingStockService.materialize(order);
        notifyNewOrder(order);
        // The buyer's own receipt. Deliberately has no bell counterpart - see
        // EmailNotificationService.orderConfirmedForBuyer - and deliberately lives
        // here rather than in the two callers, for the same reason every other
        // consequence of reaching PLACED does: there are two ways in and a receipt
        // that only one of them sends is a bug waiting for the other path.
        emailNotificationService.orderConfirmedForBuyer(order);
    }

    /**
     * ProcurePal advancing an order, or either side cancelling it. Buyer-driven
     * transitions (DELIVERED -> RECEIVED) do not come through here - receipt writes
     * stock into the buyer's inventory and is handled by OrderService.
     */
    @Transactional
    public void transition(Order order, OrderStatus target, String note, UUID actingUserId) {
        OrderStatus previous = order.getStatus();
        requireTransition(previous, target);

        order.setStatus(target);
        stamp(order, target);
        recordEvent(order, previous, target, note, actingUserId);

        if (target == OrderStatus.OUT_FOR_DELIVERY) {
            // The goods leave ProcurePal's warehouse here, so this is where their own
            // stock falls. Guarded by the transition check above: OUT_FOR_DELIVERY is
            // reachable only from PROCESSING, so it can be entered exactly once and the
            // OUT movements can only be written once.
            catalogStockService.dispatch(order, actingUserId);
        }
        if (target == OrderStatus.CANCELLED) {
            order.setCancellationReason(note);
            // Only orders that ever reached PLACED have incoming stock to give back -
            // a PENDING_PAYMENT order never created any.
            if (previous != OrderStatus.PENDING_PAYMENT) {
                incomingStockService.reverse(order);
            }
            // Nothing to give back on ProcurePal's side, ever. CANCELLED is unreachable
            // from OUT_FOR_DELIVERY onwards, so a cancelled order is by construction one
            // whose goods never left the warehouse and whose stock was never deducted.
            // Its reserved-but-undispatched quantity simply stops counting towards
            // CatalogStockService.committedQuantity, which frees the stock for the next
            // buyer with no write at all.
        }
        notifyBuyerOfStatus(order, target, note);
    }

    /** Marks an order fully received. Separate from {@link #transition} because only the buyer may do it. */
    @Transactional
    public void markReceived(Order order, UUID actingUserId) {
        OrderStatus previous = order.getStatus();
        requireTransition(previous, OrderStatus.RECEIVED);
        order.setStatus(OrderStatus.RECEIVED);
        order.setReceivedAt(OffsetDateTime.now());
        recordEvent(order, previous, OrderStatus.RECEIVED, "Received into inventory", actingUserId);
    }

    @Transactional
    public OrderStatusEvent recordEvent(
            Order order, OrderStatus from, OrderStatus to, String note, UUID actingUserId) {
        return orderStatusEventRepository.save(OrderStatusEvent.builder()
                .order(order)
                .fromStatus(from)
                .toStatus(to)
                .note(note == null || note.isBlank() ? null : note.trim())
                .createdBy(actingUserId)
                .build());
    }

    /**
     * Both channels, from one place: the bell ProcurePal staff poll, and an email to
     * the operator so a night-time order is not waiting for somebody to open the tab.
     *
     * TODO(future): when ProcurePal has dispatch riders, a new order should also
     * push to the assigned rider (FCM/SMS/WhatsApp), which is a channel neither of
     * these two covers.
     */
    @Transactional
    public void notifyNewOrder(Order order) {
        UUID operatorId = platformOwnerGuard.findPlatformOwner().map(Client::getId).orElse(null);
        String buyerName = clientRepository
                .findById(order.getClientId())
                .map(Client::getName)
                .orElse("A customer");
        notificationService.notifyAboutOrder(
                operatorId,
                NotificationType.NEW_ORDER,
                "New order " + order.getOrderNumber(),
                buyerName + " placed an order worth " + order.getCurrency() + " " + order.getTotal() + ".",
                "/app/marketplace/orders/" + order.getId(),
                order);
        emailNotificationService.newOrderPlaced(order);
    }

    @Transactional
    public void notifyPaymentReceived(Order order) {
        UUID operatorId = platformOwnerGuard.findPlatformOwner().map(Client::getId).orElse(null);
        String buyerName = clientRepository
                .findById(order.getClientId())
                .map(Client::getName)
                .orElse("A customer");
        notificationService.notifyAboutOrder(
                operatorId,
                NotificationType.PAYMENT_RECEIVED,
                "Payment received for " + order.getOrderNumber(),
                buyerName + " paid " + order.getCurrency() + " " + order.getTotal() + ".",
                "/app/marketplace/orders/" + order.getId(),
                order);
        notificationService.notifyAboutOrder(
                order.getClientId(),
                NotificationType.PAYMENT_RECEIVED,
                "Payment confirmed for " + order.getOrderNumber(),
                "We have your payment. ProcurePal will start preparing your order.",
                "/app/orders/" + order.getId(),
                order);
        emailNotificationService.paymentReceived(order);
    }

    @Transactional
    public void notifyPaymentFailed(Order order, String reason) {
        notificationService.notifyAboutOrder(
                order.getClientId(),
                NotificationType.PAYMENT_FAILED,
                "Payment did not go through for " + order.getOrderNumber(),
                (reason == null || reason.isBlank() ? "The payment attempt did not complete." : reason)
                        + " Your order is still waiting - you can try paying again.",
                "/app/orders/" + order.getId(),
                order);
        emailNotificationService.paymentFailed(order, reason);
    }

    private void notifyBuyerOfStatus(Order order, OrderStatus target, String note) {
        // DELIVERED gets its own type: it is the one status change that asks the buyer
        // to DO something (confirm receipt, which turns incoming into usable stock),
        // and the bell renders it differently for that reason.
        NotificationType type = target == OrderStatus.DELIVERED
                ? NotificationType.ORDER_DELIVERED
                : NotificationType.ORDER_STATUS_CHANGED;
        String title = target == OrderStatus.DELIVERED
                ? "Order " + order.getOrderNumber() + " was delivered"
                : "Order " + order.getOrderNumber() + " is now " + humanise(target);
        String body = target == OrderStatus.DELIVERED
                ? "Confirm receipt to move these items from incoming into your usable stock."
                : (note == null || note.isBlank() ? null : note);

        notificationService.notifyAboutOrder(
                order.getClientId(), type, title, body, "/app/orders/" + order.getId(), order);
        emailNotificationService.orderStatusChanged(order, target, note);
    }

    private static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new InvalidOrderTransitionException(from, to);
        }
    }

    /**
     * PROCESSING deliberately has no column of its own: the schema stamps the moments
     * a customer asks about ("when was it confirmed / dispatched / delivered"), and
     * "when did picking start" is answerable from order_status_events if it is ever
     * wanted.
     */
    private static void stamp(Order order, OrderStatus target) {
        OffsetDateTime now = OffsetDateTime.now();
        switch (target) {
            case PLACED -> order.setPlacedAt(now);
            case CONFIRMED -> order.setConfirmedAt(now);
            case OUT_FOR_DELIVERY -> order.setDispatchedAt(now);
            case DELIVERED -> order.setDeliveredAt(now);
            case RECEIVED -> order.setReceivedAt(now);
            case CANCELLED -> order.setCancelledAt(now);
            default -> {
                // PENDING_PAYMENT and PROCESSING have no timestamp column.
            }
        }
    }

    private static String humanise(OrderStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }
}
