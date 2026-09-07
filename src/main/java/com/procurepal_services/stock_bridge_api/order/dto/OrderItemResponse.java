package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order line, rendered entirely from the snapshots taken at checkout - never
 * from the live catalog. Renaming or repricing a product must not change what a
 * past invoice says.
 *
 * receivedQuantity/outstandingQuantity are what the "confirm receipt" screen counts
 * down, and outstandingQuantity is exactly the amount still sitting as incoming
 * stock on the buyer's own product row.
 *
 * <p>{@code buyerProductNewlyCreated} tells the confirm-receipt screen whether {@code
 * buyerProductId} is a brand new row materialize() had to create (no source-product or SKU
 * match), as opposed to one the buyer already had. That is the one window - before this line's
 * first receipt writes a StockMovement and {@code Product.unitOfMeasure} locks for good - where
 * offering to edit the SKU/unit/pack {@code IncomingStockService} copied from the seller's
 * listing is both meaningful and safe; see {@code ReceiveOrderModal}'s customization panel.
 */
public record OrderItemResponse(
        UUID id,
        UUID productId,
        UUID buyerProductId,
        String productName,
        String productSku,
        String unitOfMeasure,
        String imageUrl,
        BigDecimal unitPrice,
        int quantity,
        int receivedQuantity,
        int outstandingQuantity,
        BigDecimal lineTotal,
        boolean buyerProductNewlyCreated) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getBuyerProductId(),
                item.getProductName(),
                item.getProductSku(),
                item.getUnitOfMeasure(),
                item.getImageUrl(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getReceivedQuantity(),
                item.outstandingQuantity(),
                item.getLineTotal(),
                item.isBuyerProductNewlyCreated());
    }
}
