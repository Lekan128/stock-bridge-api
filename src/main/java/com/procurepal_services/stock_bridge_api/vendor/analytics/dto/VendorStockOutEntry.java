package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import java.util.UUID;

/**
 * A listed product a seller can no longer sell, or is about to run out of.
 *
 * <h2>Why this is in an analytics module at all</h2>
 * VENDOR_RESEARCH.md Section C item 10 records that selling out-of-stock items is
 * Jumia's single biggest cause of cancellations, and Section A lists daily stock
 * accuracy as a MUST. A stock-out is therefore not a stock report, it is the
 * leading indicator of the cancellations that show up in this same module's
 * status breakdown a week later - which is why it is reported next to them rather
 * than left to the inventory screen.
 *
 * <h2>Three numbers, because "out of stock" is not one column</h2>
 * A listed product with pallets in the warehouse can still be unsellable if every
 * unit is already committed to orders awaiting dispatch. {@code availableToSell}
 * is the number the storefront is actually advertising, and the one that decides
 * whether this row is a problem; the other two explain it. Same three-number
 * treatment, and the same reasoning, as {@code AdminCatalogProductResponse}.
 *
 * @param quantityOnHand what is physically on the shelf - the raw column.
 * @param committedQuantity units owed to orders that have not left the warehouse.
 * @param availableToSell on-hand minus committed, floored at zero.
 * @param listed whether the seller currently has it up for sale. Both values
 *     appear: an unlisted product at zero is a decision, a LISTED product at zero
 *     is a storefront entry a buyer is about to fail to order, and only the second
 *     is urgent.
 * @param lowStockThreshold the seller's own alert level, or null if they never set
 *     one. Null is why {@code availableToSell == 0} is reported for every listed
 *     product regardless: a vendor who set no threshold still needs to know they
 *     have run out.
 */
public record VendorStockOutEntry(
        UUID productId,
        String name,
        String sku,
        int quantityOnHand,
        int committedQuantity,
        int availableToSell,
        boolean listed,
        Integer lowStockThreshold) {
}
