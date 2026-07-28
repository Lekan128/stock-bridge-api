package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Revenue split by catalog category.
 *
 * {@code totalRevenue} is echoed alongside the slices so the client can render "₦X of ₦Y"
 * without re-summing, and so a reader can see immediately that the shares add to one.
 * It equals the summary's merchandiseRevenue, NOT its grossRevenue - see
 * {@link CategoryMixEntry#revenue}.
 */
public record CategoryMixResponse(BigDecimal totalRevenue, List<CategoryMixEntry> categories) {
}
