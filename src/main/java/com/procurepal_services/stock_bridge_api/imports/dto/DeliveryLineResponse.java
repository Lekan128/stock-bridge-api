package com.procurepal_services.stock_bridge_api.imports.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of the "Record a delivery" screen (BULK_IMPORT_CX_PLAN.md task 2.1): one product, bought
 * one way - the same row the stock sheet has.
 *
 * @param unit what to send back as the line's {@code unit}: {@code "BAG:50"} for a pack, the stock
 *     unit's code otherwise ({@code UnitOptions.key}).
 * @param comesIn the words for it - "Bag · 50 kg", "Loose · kg", "Piece".
 * @param pack true for a pack row, false for the product's own unit.
 * @param lastPrice what was last paid for ONE of these - per bag on a bag row. Absent when unknown.
 * @param supplierName the product's usual supplier. Absent when it has none.
 */
public record DeliveryLineResponse(
        UUID productId,
        String productName,
        String sku,
        String unit,
        String comesIn,
        boolean pack,
        BigDecimal lastPrice,
        String supplierName) {
}
