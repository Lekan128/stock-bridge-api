package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import java.math.BigDecimal;

/**
 * One bucket of a seller's own revenue-over-time series.
 *
 * Buckets are zero-filled by the query (a generate_series LEFT JOINed to orders),
 * so a quiet week comes back as a real zero rather than a missing point - a line
 * chart that silently skips empty days draws a rise that did not happen.
 *
 * <p>No {@code buyingCompanies} counterpart to {@code RevenuePoint}'s: see
 * {@link VendorSalesPeriodMetrics} for why buyer-identity aggregates are out of
 * scope for a seller's own view.
 *
 * @param period bucket START date as "YYYY-MM-DD". Week buckets start Monday
 *     (Postgres date_trunc('week', ...)), month buckets on the 1st.
 * @param revenue gross revenue (goods + delivery) of this seller's revenue-bearing
 *     orders dated in this bucket.
 * @param orderCount those orders.
 * @param unitsSold order_items.quantity summed over them.
 */
public record VendorRevenuePoint(String period, BigDecimal revenue, long orderCount, long unitsSold) {
}
