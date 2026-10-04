package com.procurepal_services.stock_bridge_api.product.sync;

import com.procurepal_services.stock_bridge_api.product.StockStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product as the on-device catalogue stores it (A3): exactly what the Inventory list shows,
 * filters and sorts on, and nothing else. Costs, descriptions, suppliers and history stay on the
 * product page, which still asks the server for them.
 *
 * <p>{@code isLowStock} and {@code stockStatus} are computed here, by the same
 * {@link StockStatus} functions the list uses, so the phone never re-derives them and the two can
 * never disagree about which chip a product belongs under.
 */
public record ProductSyncRow(
        UUID id,
        String name,
        String sku,
        String barcode,
        String imageUrl,
        UUID categoryId,
        String categoryName,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize,
        boolean hasMultiplePacks,
        BigDecimal unitPrice,
        int quantityOnHand,
        int incomingQuantity,
        BigDecimal expectedQuantity,
        Integer lowStockThreshold,
        boolean active,
        boolean isLowStock,
        StockStatus stockStatus) {
}
