package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of a seller's own top-products ranking, aggregated over order_items.
 *
 * @param revenue sum of order_items.line_total over this seller's revenue-bearing
 *     orders dated in the window. Line totals only: the delivery fee is charged per
 *     order and cannot be attributed to a product without inventing an allocation
 *     rule. So summing this does NOT reproduce the summary's grossRevenue - it
 *     reproduces merchandiseRevenue, exactly as on the marketplace view.
 * @param quantitySold sum of order_items.quantity on the same basis.
 * @param orderCount distinct orders the product appeared on.
 * @param name / sku / categoryName read from the CURRENT catalogue row, not the
 *     order-line snapshot, so a renamed product aggregates as one row rather than
 *     splitting in two.
 *
 * <p>No {@code buyingCompanies} column, unlike {@code TopSellingProductEntry}:
 * "how many different companies buy this" is a buyer aggregate, and the scope here
 * is the seller's own sales. See {@link VendorSalesPeriodMetrics}.
 */
public record VendorTopProductEntry(
        UUID productId,
        String name,
        String sku,
        String categoryName,
        BigDecimal revenue,
        long quantitySold,
        long orderCount) {
}
