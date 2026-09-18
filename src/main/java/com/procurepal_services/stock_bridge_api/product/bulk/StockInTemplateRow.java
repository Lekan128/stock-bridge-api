package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the stock sheet: one product, bought one way (BULK_IMPORT_CX_PLAN.md task 1.4).
 *
 * <h2>One row per way of buying, not one per product</h2>
 * A product bought in 30 kg baskets and sometimes loose by the kg gets two rows - "Basket · 30 kg"
 * and "Loose · kg" - so the person filling the sheet only ever types a number on the row that
 * matches how the delivery came. The sheet used to ask them to copy a phrase such as "Carton of
 * 12 g" from one cell into another, and every stray space was an error row.
 *
 * @param productId written to the hidden Ref column through {@link ProductRefs}, which is how the
 *     row is matched back; never shown.
 * @param sku reference only ("Your code").
 * @param productName reference only - what the person reads to find the row.
 * @param vendorName the product's preferred supplier, or null when it has none yet.
 * @param option the way of buying this row stands for - a pack, or the stock unit.
 * @param lastPricePerOption what was last paid for ONE of {@code option} (per bag on a bag row),
 *     or null when nothing is known. Shown for reference; the price column itself starts blank.
 */
public record StockInTemplateRow(
        UUID productId,
        String sku,
        String productName,
        String vendorName,
        UnitOption option,
        BigDecimal lastPricePerOption) {
}
