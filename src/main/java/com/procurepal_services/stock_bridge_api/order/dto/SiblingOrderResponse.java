package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Another order from the same checkout - just enough to say "and this one, from that
 * seller, for this much, currently here" and link to it.
 *
 * <h2>Why a summary and not an OrderResponse</h2>
 * Because OrderResponse now carries its own siblings, and nesting the full record
 * would recurse: three orders would each embed the other two, which would each embed
 * the other two. A deliberately shallow record ends the recursion by construction
 * rather than by a depth counter somebody has to remember.
 *
 * <p>It also keeps the payload honest about cost. The buyer's order detail page needs
 * to answer "what else came out of this basket" in a sentence; it does not need every
 * sibling's line items and status history, and fetching them would turn one order page
 * into N.
 */
public record SiblingOrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        BigDecimal total,
        OrderSellerResponse seller) {

    public static SiblingOrderResponse of(Order order, OrderSellerResponse seller) {
        return new SiblingOrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getTotal(),
                seller);
    }
}
