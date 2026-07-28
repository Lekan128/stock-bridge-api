package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The read-only projection of an order that the payment module works from.
 *
 * <p>{@code total} is the authoritative figure the amount check runs against, and
 * the customer fields are what Monnify's hosted checkout displays. Deliberately
 * carries no delivery address or line items: the payment module has no business
 * knowing what was bought.
 *
 * <p>Note there is no {@code clientId} here. Ownership of an order is asserted by
 * the payment module against {@code OrderRepository.findByIdForCurrentTenant}
 * before this is ever loaded, so a caller cannot open a checkout - or read a
 * verification - for a company they do not belong to.
 */
public record OrderPaymentContext(
        UUID orderId,
        String orderNumber,
        BigDecimal total,
        String currency,
        OrderStatus status,
        PaymentStatus paymentStatus,
        String customerName,
        String customerEmail,
        String customerPhone) {
}
