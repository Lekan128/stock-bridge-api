package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One order, in full. The same record serves the buyer's order detail page and
 * ProcurePal's fulfilment detail page - the only difference is that {@code customer}
 * is populated for the latter, and {@code allowedNextStatuses} tells the fulfilment
 * UI which buttons to render instead of making it re-implement the state machine.
 *
 * {@code canCancel} / {@code canReceive} are computed server-side for the same
 * reason: the rule for "may this buyer cancel" is the state machine plus the
 * buyer-driven carve-out, and a frontend copy of it drifts.
 *
 * <h2>One checkout can have produced several of these</h2>
 * {@code seller} names who is fulfilling THIS order, and {@code checkoutGroupId} plus
 * {@code siblingOrders} say which basket it came out of. Without them a buyer who
 * checked out one cart containing three sellers' goods would see three unexplained
 * orders with three order numbers and three delivery fees, and no way to tell they
 * were one purchase. See {@code Order.checkoutGroupId}.
 */
public record OrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,
        String currency,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        /**
         * Who sold this order - name and logo only, on the buyer-facing surfaces for
         * the same reason MarketplaceSellerResponse withholds contact details.
         * Resolved from the seller OF RECORD, so an order placed with a vendor who has
         * since been suspended still renders their name rather than a blank.
         */
        OrderSellerResponse seller,
        /** The basket this order came out of. Shared with {@link #siblingOrders}. */
        UUID checkoutGroupId,
        /**
         * The OTHER orders the same checkout produced, oldest number first - empty for
         * the ordinary single-seller case. Deliberately a light summary rather than
         * full OrderResponses: rendering "your basket also became these" needs a number,
         * a seller and a total, and nesting complete orders would recurse.
         */
        List<SiblingOrderResponse> siblingOrders,
        OrderDeliveryResponse delivery,
        String customerNote,
        String cancellationReason,
        List<OrderItemResponse> items,
        List<OrderStatusEventResponse> events,
        OrderCustomerResponse customer,
        UUID placedByUserId,
        String placedByUsername,
        int itemCount,
        int distinctItemCount,
        boolean fullyReceived,
        boolean canCancel,
        boolean canReceive,
        List<OrderStatus> allowedNextStatuses,
        OffsetDateTime placedAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime dispatchedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime receivedAt,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static OrderResponse of(
            Order order,
            List<OrderItem> items,
            List<OrderStatusEvent> events,
            OrderCustomerResponse customer,
            String placedByUsername,
            OrderSellerResponse seller,
            List<SiblingOrderResponse> siblingOrders) {
        boolean fullyReceived = !items.isEmpty() && items.stream().allMatch(item -> item.outstandingQuantity() == 0);
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getPaymentMethod(),
                order.getCurrency(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotal(),
                seller,
                order.getCheckoutGroupId(),
                siblingOrders,
                OrderDeliveryResponse.from(order),
                order.getCustomerNote(),
                order.getCancellationReason(),
                items.stream().map(OrderItemResponse::from).toList(),
                events.stream().map(OrderStatusEventResponse::from).toList(),
                customer,
                order.getPlacedBy(),
                placedByUsername,
                items.stream().mapToInt(OrderItem::getQuantity).sum(),
                items.size(),
                fullyReceived,
                // The buyer may pull out before ProcurePal has started picking. After
                // that it is a conversation, not a button.
                order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.PLACED,
                order.getStatus() == OrderStatus.DELIVERED && !fullyReceived,
                List.copyOf(order.getStatus().allowedNextStatuses()),
                order.getPlacedAt(),
                order.getConfirmedAt(),
                order.getDispatchedAt(),
                order.getDeliveredAt(),
                order.getReceivedAt(),
                order.getCancelledAt(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
