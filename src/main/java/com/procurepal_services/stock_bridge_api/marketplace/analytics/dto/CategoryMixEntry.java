package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One slice of the category mix, ordered revenue-descending.
 *
 * @param categoryId null for products that have no category. Because
 *     {@code default-property-inclusion: non_null} is set app-wide, that field is OMITTED
 *     from the JSON rather than sent as null - clients must treat it as undefined. The
 *     name is never null: the uncategorised slice is labelled explicitly so it cannot be
 *     mistaken for a rendering bug.
 * @param revenue sum of order_items.line_total for products in this category, over
 *     revenue-bearing orders dated in the window. Goods only - the per-order delivery fee
 *     is not attributable to a category.
 * @param quantitySold units on the same basis.
 * @param orderCount distinct orders containing at least one product of this category. The
 *     column therefore does NOT sum to the period's order count: a mixed basket is
 *     counted once in each category it touches.
 * @param share {@code revenue / totalRevenue} as a 0..1 fraction to 4dp. Zero for every
 *     row when the period sold nothing, rather than a division by zero.
 */
public record CategoryMixEntry(
        UUID categoryId,
        String categoryName,
        BigDecimal revenue,
        long quantitySold,
        long orderCount,
        BigDecimal share) {
}
