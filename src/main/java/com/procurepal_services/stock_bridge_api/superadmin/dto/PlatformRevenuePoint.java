package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;

/**
 * One bucket of the marketplace-wide revenue series - "how it grows", as a line rather
 * than a single delta.
 *
 * <p>Buckets are zero-filled by the query (a generate_series LEFT JOINed to orders), so a
 * quiet week comes back as a real zero rather than a missing point: a line chart that
 * silently skips empty days draws a rising trend that did not happen.
 *
 * @param period bucket START date as "YYYY-MM-DD". Week buckets start Monday (Postgres
 *     date_trunc('week', ...)), month buckets on the 1st - the same convention every other
 *     series in the application uses.
 * @param revenue gross revenue (goods + delivery) of revenue-bearing orders dated in this
 *     bucket, across every seller matching the request's filters.
 * @param orderCount revenue-bearing orders dated in this bucket.
 * @param unitsSold order_items.quantity summed over those orders.
 * @param sellingSellerCount distinct sellers that took an order in this bucket. Summing
 *     this across buckets does NOT give the period's total - the same seller is counted
 *     once per bucket it traded in.
 */
public record PlatformRevenuePoint(
        String period, BigDecimal revenue, long orderCount, long unitsSold, long sellingSellerCount) {
}
