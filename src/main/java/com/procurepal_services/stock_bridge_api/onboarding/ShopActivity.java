package com.procurepal_services.stock_bridge_api.onboarding;

import java.time.OffsetDateTime;

/**
 * How far a shop has got (LANDING_PAGE_PLAN.md §4, "what we measure"). Counts the shop's own work
 * only: opening stock written by an import, and anything Procurepaddy support did inside the shop,
 * load the shop but are not the shop using it.
 *
 * @param products           active products
 * @param stockChanges       the shop's own stock changes (deliveries, sales, counts), all time
 * @param firstStockChangeAt the first of them; null until there is one
 * @param counts             of those, shelf counts
 * @param changesLast7Days   own stock changes in the last 7 days
 * @param activeDaysFirstWeek days, in the shop's first 7, with at least one own stock change
 * @param lowStock           active products at or under their low-stock level
 * @param staff              active users other than the owner and Procurepaddy support
 * @param listFiles          files sent with "Send us your list"
 */
public record ShopActivity(
        int products,
        OffsetDateTime firstProductAt,
        int stockChanges,
        OffsetDateTime firstStockChangeAt,
        int counts,
        int changesLast7Days,
        int activeDaysFirstWeek,
        int lowStock,
        int staff,
        int listFiles) {
}
