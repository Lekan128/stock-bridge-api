package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of ProcurePal's top-products ranking, aggregated over order_items across
 * ProcurePal's OWN sales. A third-party vendor's best seller is not a merchandising
 * signal ProcurePal can act on and never appears here.
 *
 * Named TopSellingProductEntry rather than TopProductEntry to stay distinguishable from
 * {@code analytics.dto.TopProductEntry}, which ranks a tenant's own stock MOVEMENTS. The
 * two answer different questions and must never be confused in an import list.
 *
 * @param revenue sum of order_items.line_total over revenue-bearing orders dated in the
 *     window. Line totals only: delivery fee is charged per order, not per product, so it
 *     cannot be attributed to a product without inventing an allocation rule. This is why
 *     summing product revenue does NOT reproduce the summary's grossRevenue - it
 *     reproduces merchandiseRevenue.
 * @param quantitySold sum of order_items.quantity on the same basis.
 * @param orderCount distinct orders the product appeared on.
 * @param buyingCompanies distinct buyer tenants that bought it - separates "one customer
 *     bulk-buying" from "everybody wants this".
 * @param name / sku / categoryName read from the CURRENT catalog row, not the order-line
 *     snapshot, so a renamed product aggregates as one row instead of one per old name.
 */
public record TopSellingProductEntry(
        UUID productId,
        String name,
        String sku,
        String categoryName,
        BigDecimal revenue,
        long quantitySold,
        long orderCount,
        long buyingCompanies) {
}
