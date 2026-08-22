package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One past order in a vendor's purchase history, with its lines.
 *
 * <h2>Paginated by ORDER, not by line</h2>
 * Odoo's Purchases smart button opens a list of purchase orders, and that is the
 * unit a buyer reasons in: "the delivery we took in March", not "line 4 of 11".
 * Paginating lines instead would let one order's lines straddle a page boundary,
 * which makes a total on screen mean nothing.
 *
 * <h2>Cancelled orders appear here and nowhere else</h2>
 * They are excluded from {@link VendorSpendSummary} and from last purchase price -
 * a cancelled order is a purchase that did not happen - but they are shown in this
 * list, badged with their status, because "we ordered from them and pulled out" is
 * a fact about the relationship that a buyer looking at their supplier's history
 * wants to see. Orders that never reached PLACED are excluded from all three: an
 * unpaid Monnify order is not yet a purchase in any sense.
 *
 * <h2>Every field is a snapshot</h2>
 * Straight off {@link OrderItem}, which froze them at checkout. Renaming or
 * repricing a product must not change what a past purchase says it cost - which is
 * also why this screen is answerable at all after a seller delists an item.
 */
public record VendorPurchaseResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        /* Never null in this list: not having reached PLACED is what keeps an order out of it. */
        OffsetDateTime placedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime receivedAt,
        String currency,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        List<VendorPurchaseLine> lines) {

    /**
     * One line of one past purchase. {@code buyerProductId} is what links it back
     * to the buyer's own inventory row, and is null only for an order whose lines
     * were never materialised into inventory.
     */
    public record VendorPurchaseLine(
            UUID orderItemId,
            UUID buyerProductId,
            String productName,
            String productSku,
            String unitOfMeasure,
            BigDecimal unitPrice,
            int quantity,
            int receivedQuantity,
            BigDecimal lineTotal) {

        public static VendorPurchaseLine from(OrderItem item) {
            return new VendorPurchaseLine(
                    item.getId(),
                    item.getBuyerProductId(),
                    item.getProductName(),
                    item.getProductSku(),
                    item.getUnitOfMeasure(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.getReceivedQuantity(),
                    item.getLineTotal());
        }
    }

    public static VendorPurchaseResponse from(Order order, List<OrderItem> items) {
        return new VendorPurchaseResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getPlacedAt(),
                order.getDeliveredAt(),
                order.getReceivedAt(),
                order.getCurrency(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotal(),
                items.stream().map(VendorPurchaseLine::from).toList());
    }
}
