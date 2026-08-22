package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * List-row projection. A separate record from {@link OrderResponse} rather than the
 * same one with empty collections, because a 50-row fulfilment queue that carried
 * every line item and status event of every order would be an N+1 in two directions
 * for data no list renders.
 *
 * <p>{@code seller} and {@code checkoutGroupId} are here because a buyer's order
 * history stopped being a flat list when checkout started splitting: three rows from
 * one basket need to say who each is from and that they belong together. The sibling
 * orders themselves are NOT here - see OrderResponseAssembler for why resolving them
 * per row would turn one list into N queries for information the row has no room to
 * show.
 */
public record OrderSummaryResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,
        String currency,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        int itemCount,
        String deliveryCity,
        String deliveryState,
        OrderCustomerResponse customer,
        /** Who is fulfilling this one - name and logo only. */
        OrderSellerResponse seller,
        /** Rows sharing this value came out of one checkout. */
        UUID checkoutGroupId,
        OffsetDateTime placedAt,
        OffsetDateTime createdAt) {

    public static OrderSummaryResponse of(
            Order order, int itemCount, OrderCustomerResponse customer, OrderSellerResponse seller) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getPaymentMethod(),
                order.getCurrency(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotal(),
                itemCount,
                order.getDeliveryCity(),
                order.getDeliveryState(),
                customer,
                seller,
                order.getCheckoutGroupId(),
                order.getPlacedAt(),
                order.getCreatedAt());
    }
}
