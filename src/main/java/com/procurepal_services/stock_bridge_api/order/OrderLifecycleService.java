package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorLinkService;
import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.marketplace.PlatformOwnerGuard;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderStatusEventRepository;
import com.procurepal_services.stock_bridge_api.settlement.VendorLedgerService;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final CompanyVendorLinkService companyVendorLinkService;
    private final CatalogStockService catalogStockService;
    private final NotificationService notificationService;
    private final EmailNotificationService emailNotificationService;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final ClientRepository clientRepository;
    private final VendorLedgerService vendorLedgerService;

    /**
     * The status changes worth an email to the buyer: the ones they wait for or must act
     * on - the same set mainstream storefronts mail customers about (shipped/out for
     * delivery, delivered, cancelled). CONFIRMED and PROCESSING are the seller's internal
     * steps; the buyer already has a receipt, so those stay in the bell and on the order
     * page instead of becoming two more emails per order nobody opens.
     */
    static final java.util.Set<OrderStatus> EMAILED_STATUSES =
            java.util.EnumSet.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, OrderStatus.CANCELLED);

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
        // The seller joins the buyer's own vendor directory, and the products this
        // order just put into their inventory point at it. Here rather than in
        // OrderService for the same reason everything else in this method is: an
        // order reaches PLACED from two directions (a COD checkout and a verified
        // Monnify payment), and a consequence wired into only one of them is a bug
        // waiting for the other path. Runs AFTER materialize, which is what created
        // the buyer's product rows this links. Idempotent - see the service.
        companyVendorLinkService.recordPurchase(order);
        notifyNewOrder(order);
        // NOT the buyer's receipt. One checkout can place several orders at once, and the
        // buyer gets ONE receipt for all of them - so it is sent by the two callers after
        // their loop, through notifyCheckoutConfirmed, rather than once per order here.
    }

    /**
     * The buyer's side of a checkout becoming real: one receipt email for every order it
     * placed (a section per seller), and - for a card checkout, where the buyer may not be
     * watching the screen when the webhook lands - a bell entry per order.
     *
     * <p>Both ways an order reaches PLACED (a pay-on-delivery checkout in OrderService, a
     * verified payment in OrderPaymentApplicationService) call this once, after placing
     * every order of the group. A receipt only one of them sent would be a bug waiting
     * for the other path, which is why it lives here.
     */
    @Transactional
    public void notifyCheckoutConfirmed(List<Order> placed) {
        if (placed == null || placed.isEmpty()) {
            return;
        }
        for (Order order : placed) {
            if (order.getPaymentStatus() == PaymentStatus.PAID) {
                notificationService.notifyAboutOrder(
                        order.getClientId(),
                        NotificationType.PAYMENT_RECEIVED,
                        "Payment confirmed for " + order.getOrderNumber(),
                        "We have your payment. The seller will start preparing your order.",
                        "/app/orders/" + order.getId(),
                        order);
            }
        }
        emailNotificationService.checkoutConfirmedForBuyer(placed);
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
            applyCancellation(order, previous, note, actingUserId);
        }
        notifyBuyerOfStatus(order, target, note);
    }

    /**
     * Cancels a checkout that was never paid for, WITHOUT telling anybody.
     *
     * <h2>Why this is silent when every other cancellation notifies</h2>
     * The buyer did not ask for this and ProcurePal did not decide it - a clock did,
     * and the buyer was shown that clock at checkout and on the order page ("unpaid
     * orders are cancelled after 24 hours"). An email at this point would be news to
     * nobody: they already know they did not pay. It is also the one cancellation
     * driven by a scheduler rather than a person, which is exactly the kind of
     * sender that turns a notification into a loop the moment its guard is wrong -
     * as the abandoned-checkout sweep did before this method existed, re-sending
     * "payment did not go through" to every stale order every five minutes.
     *
     * <p>The audit trail is still written (status event plus cancellation reason),
     * so the order page explains what happened to anyone who looks.
     */
    @Transactional
    public void expireUnpaid(Order order, String note) {
        OrderStatus previous = order.getStatus();
        requireTransition(previous, OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        stamp(order, OrderStatus.CANCELLED);
        recordEvent(order, previous, OrderStatus.CANCELLED, note, null);
        applyCancellation(order, previous, note, null);
    }

    private void applyCancellation(Order order, OrderStatus previous, String note, UUID actingUserId) {
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
        //
        // And the money half. Today this finds nothing to reverse for exactly the
        // reason above - an order that accrued cannot subsequently be cancelled, so
        // there is no accrual here to undo - and it is wired anyway, quietly, for
        // the same reason everything else in this method is: the day somebody widens
        // the state machine to allow a post-delivery cancellation, a vendor being
        // paid for goods that came back is not a bug anyone would notice quickly.
        // See VendorLedgerService.reverseForCancellation.
        vendorLedgerService.reverseForCancellation(order, note, actingUserId);
    }

    /**
     * Marks an order fully received. Separate from {@link #transition} because only the
     * buyer may do it - and, since M7, because it is the moment a VENDOR'S MONEY STOPS
     * BEING HELD IN ESCROW.
     *
     * <h2>Why the accrual hangs off RECEIVED and not DELIVERED</h2>
     * DELIVERED is the SELLER's assertion that the goods arrived, and on a vendor's own
     * order the vendor is the one who sets it. Paying a vendor on their own say-so is
     * exactly what escrow exists to prevent. RECEIVED is the BUYER's assertion, it is
     * the transition {@link OrderStatus#isBuyerDriven()} exists to protect, and it
     * already means ALL of it - partial receipt deliberately leaves an order at
     * DELIVERED with the remainder still incoming. "Fully confirmed" and RECEIVED are
     * the same sentence. The full argument, including what happens when a buyer never
     * confirms, is on {@code VendorLedgerService}.
     *
     * <p>Here rather than in OrderService for the reason every other consequence in
     * this class is here: a consequence wired into one caller is a bug waiting for the
     * next one. Runs AFTER the status is set, so an accrual can never exist for an
     * order the transition check would have refused. Idempotent - see the service.
     */
    @Transactional
    public void markReceived(Order order, UUID actingUserId) {
        OrderStatus previous = order.getStatus();
        requireTransition(previous, OrderStatus.RECEIVED);
        order.setStatus(OrderStatus.RECEIVED);
        order.setReceivedAt(OffsetDateTime.now());
        recordEvent(order, previous, OrderStatus.RECEIVED, "Received into inventory", actingUserId);

        vendorLedgerService.accrueForConfirmedDelivery(
                order, order.getReceivedAt(), "Delivery confirmed by buyer", actingUserId);
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
     * Both channels, from one place, to the order's SELLER: the bell, and an email so a
     * night-time order is not waiting for somebody to open the tab.
     *
     * <p>The seller, not always ProcurePal. The fulfilment queue is seller-scoped - a
     * vendor sees and advances exactly its own orders and ProcurePal cannot open them -
     * so telling ProcurePal about a vendor's order reached the one reader who could do
     * nothing, and never the vendor who had to pack it.
     *
     * TODO(future): when ProcurePal has dispatch riders, a new order should also
     * push to the assigned rider (FCM/SMS/WhatsApp), which is a channel neither of
     * these two covers.
     */
    @Transactional
    public void notifyNewOrder(Order order) {
        boolean paid = order.getPaymentStatus() == PaymentStatus.PAID;
        notificationService.notifyAboutOrder(
                sellerOf(order),
                NotificationType.NEW_ORDER,
                "New order " + order.getOrderNumber(),
                buyerNameOf(order) + (paid ? " placed and paid for" : " placed") + " an order worth "
                        + order.getCurrency() + " " + order.getTotal() + (paid ? "." : " (pay on delivery)."),
                "/app/marketplace/orders/" + order.getId(),
                order);
        emailNotificationService.newOrderForSeller(order);
    }

    /**
     * Money arrived for orders that were ALREADY placed - pay-on-delivery settled by card,
     * or a payment that landed after the seller moved on. Each seller hears about their
     * order; the buyer gets one email for the whole payment. A normal card checkout never
     * comes here: its receipt and the new-order notices already say "paid".
     */
    @Transactional
    public void notifyLatePayment(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (Order order : orders) {
            notificationService.notifyAboutOrder(
                    sellerOf(order),
                    NotificationType.PAYMENT_RECEIVED,
                    "Payment received for " + order.getOrderNumber(),
                    buyerNameOf(order) + " paid " + order.getCurrency() + " " + order.getTotal() + ".",
                    "/app/marketplace/orders/" + order.getId(),
                    order);
            notificationService.notifyAboutOrder(
                    order.getClientId(),
                    NotificationType.PAYMENT_RECEIVED,
                    "Payment confirmed for " + order.getOrderNumber(),
                    "We have your payment.",
                    "/app/orders/" + order.getId(),
                    order);
        }
        emailNotificationService.latePaymentReceived(orders);
    }

    /** The seller recorded pay-on-delivery cash as received: the buyer's receipt, nobody else's. */
    @Transactional
    public void notifyCashCollected(Order order) {
        notificationService.notifyAboutOrder(
                order.getClientId(),
                NotificationType.PAYMENT_RECEIVED,
                "Payment confirmed for " + order.getOrderNumber(),
                "We have your payment.",
                "/app/orders/" + order.getId(),
                order);
        emailNotificationService.paymentReceiptForBuyer(List.of(order));
    }

    /**
     * A card payment was declined or came up short. A bell entry per order (each is what
     * the buyer sees in their history) but ONE email for the attempt - it was one payment.
     */
    @Transactional
    public void notifyPaymentFailed(List<Order> orders, String reason) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (Order order : orders) {
            notificationService.notifyAboutOrder(
                    order.getClientId(),
                    NotificationType.PAYMENT_FAILED,
                    "Payment did not go through for " + order.getOrderNumber(),
                    (reason == null || reason.isBlank() ? "The payment attempt did not complete." : reason)
                            + " Your order is still waiting - you can try paying again.",
                    "/app/orders/" + order.getId(),
                    order);
        }
        emailNotificationService.paymentFailed(orders, reason);
    }

    /**
     * The BUYER cancelling their own order. Same state change as a seller cancelling, but
     * the audience flips: the buyer pressed the button and needs no email about it, while
     * the seller - who may be about to pick it - must be told.
     */
    @Transactional
    public void cancelByBuyer(Order order, String note, UUID actingUserId) {
        OrderStatus previous = order.getStatus();
        requireTransition(previous, OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        stamp(order, OrderStatus.CANCELLED);
        recordEvent(order, previous, OrderStatus.CANCELLED, note, actingUserId);
        applyCancellation(order, previous, note, actingUserId);

        // An unpaid order was never real to the seller (they were never told about it),
        // so there is nothing to un-tell.
        if (previous == OrderStatus.PENDING_PAYMENT) {
            return;
        }
        notificationService.notifyAboutOrder(
                sellerOf(order),
                NotificationType.ORDER_STATUS_CHANGED,
                "Order " + order.getOrderNumber() + " was cancelled by the customer",
                note == null || note.isBlank() ? "Do not prepare or dispatch it." : "Reason: " + note.trim(),
                "/app/marketplace/orders/" + order.getId(),
                order);
        emailNotificationService.orderCancelledByBuyer(order, note);
    }

    private UUID sellerOf(Order order) {
        if (order.getSellerClientId() != null) {
            return order.getSellerClientId();
        }
        // Pre-V11 rows had no seller; they were all ProcurePal's.
        return platformOwnerGuard.findPlatformOwner().map(Client::getId).orElse(null);
    }

    private String buyerNameOf(Order order) {
        return clientRepository.findById(order.getClientId()).map(Client::getName).orElse("A customer");
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
        if (EMAILED_STATUSES.contains(target)) {
            emailNotificationService.orderStatusChanged(order, target, note);
        }
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
