package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;

/**
 * One bucket of ProcurePal's own revenue-over-time series. Every figure counts only
 * orders ProcurePal sold - see MarketplacePeriodMetrics.
 *
 * Buckets are zero-filled by the query (a generate_series LEFT JOINed to orders), so a
 * quiet week comes back as a real zero rather than a missing point - a line chart that
 * silently skips empty days draws a rising trend that did not happen.
 *
 * @param period bucket START date as "YYYY-MM-DD". Week buckets start Monday
 *     (Postgres date_trunc('week', ...)), month buckets on the 1st - matching the
 *     convention MovementsOverTimePoint already established for the tenant dashboard.
 * @param revenue gross revenue (goods + delivery) of revenue-bearing orders dated in
 *     this bucket. See MarketplacePeriodMetrics for what "revenue-bearing" excludes.
 * @param orderCount revenue-bearing orders dated in this bucket.
 * @param unitsSold order_items.quantity summed over those orders.
 * @param buyingCompanies distinct buyer tenants that ordered in this bucket. Summing
 *     this across buckets does NOT give the period's active companies - the same
 *     company is counted once per bucket it bought in.
 */
public record RevenuePoint(String period, BigDecimal revenue, long orderCount, long unitsSold, long buyingCompanies) {
}
