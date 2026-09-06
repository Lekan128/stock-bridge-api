package com.procurepal_services.stock_bridge_api.purchase.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of "everything this company has bought from its suppliers" - either a placed
 * marketplace {@link Order} or a manual {@link StockMovement} entered against a company vendor.
 *
 * <h2>Why one shape for two different records</h2>
 * The purchase-history screen (and the company-wide one above it) reasons about both the same
 * way: a date, a supplier, what was bought, and what it cost. Keeping them as two separate DTOs
 * would push that merge into the frontend, which cannot sort or paginate a mixed feed across two
 * independently-paged API responses correctly - only the database can do that in one ORDER BY.
 * {@link #source} is what tells a reader (and the screen's filter) which fields are meaningful:
 * {@code orderId}/{@code orderNumber}/{@code status}/{@code paymentStatus} are only ever set for
 * {@link PurchaseSource#MARKETPLACE_ORDER}; {@code note} only for
 * {@link PurchaseSource#MANUAL_STOCK_IN}.
 *
 * <h2>A manual stock-in is always exactly one line</h2>
 * Unlike an order, which may bundle several products into one checkout, a single stock-in call
 * records one product's delivery. {@link #lines} still holds a list, of size one, so the screen
 * can render both kinds of entry with the same card and line table.
 */
public record PurchaseHistoryEntry(
        UUID id,
        PurchaseSource source,
        UUID companyVendorId,
        String vendorName,
        /* MARKETPLACE_ORDER only. */
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        OffsetDateTime occurredAt,
        String currency,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        /* MANUAL_STOCK_IN only. */
        String note,
        List<PurchaseHistoryLine> lines) {

    private static final String CURRENCY = "NGN";

    public record PurchaseHistoryLine(
            UUID orderItemId,
            UUID stockMovementId,
            UUID buyerProductId,
            String productName,
            String productSku,
            String unitOfMeasure,
            BigDecimal unitPrice,
            int quantity,
            int receivedQuantity,
            BigDecimal lineTotal) {

        static PurchaseHistoryLine fromOrderItem(OrderItem item) {
            return new PurchaseHistoryLine(
                    item.getId(),
                    null,
                    item.getBuyerProductId(),
                    item.getProductName(),
                    item.getProductSku(),
                    item.getUnitOfMeasure(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.getReceivedQuantity(),
                    item.getLineTotal());
        }

        static PurchaseHistoryLine fromStockMovement(StockMovement movement) {
            BigDecimal unitPrice = movement.getUnitPriceAtTime();
            BigDecimal lineTotal = unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(movement.getQuantity()));
            return new PurchaseHistoryLine(
                    null,
                    movement.getId(),
                    movement.getProduct().getId(),
                    movement.getProduct().getName(),
                    movement.getProduct().getSku(),
                    movement.getProduct().getUnitOfMeasure(),
                    unitPrice,
                    movement.getQuantity(),
                    // Fully received by definition - a manual stock-in has no separate
                    // dispatch/receipt step the way a marketplace order does.
                    movement.getQuantity(),
                    lineTotal);
        }
    }

    public static PurchaseHistoryEntry fromOrder(Order order, List<OrderItem> items, UUID companyVendorId, String vendorName) {
        return new PurchaseHistoryEntry(
                order.getId(),
                PurchaseSource.MARKETPLACE_ORDER,
                companyVendorId,
                vendorName,
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getPlacedAt(),
                order.getCurrency(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotal(),
                null,
                items.stream().map(PurchaseHistoryLine::fromOrderItem).toList());
    }

    public static PurchaseHistoryEntry fromStockMovement(StockMovement movement, String vendorName) {
        PurchaseHistoryLine line = PurchaseHistoryLine.fromStockMovement(movement);
        // Null, not zero, when no price was recorded - "we don't know what this cost" and "this
        // cost nothing" are different claims, and the UI must render the former as an em dash
        // rather than ₦0.00 (see VendorProductPrice.lastPurchaseUnitPrice for the same rule).
        return new PurchaseHistoryEntry(
                movement.getId(),
                PurchaseSource.MANUAL_STOCK_IN,
                movement.getCompanyVendor().getId(),
                vendorName,
                null,
                null,
                null,
                movement.getOccurredAt(),
                CURRENCY,
                line.lineTotal(),
                BigDecimal.ZERO,
                line.lineTotal(),
                movement.getNote(),
                List.of(line));
    }
}
