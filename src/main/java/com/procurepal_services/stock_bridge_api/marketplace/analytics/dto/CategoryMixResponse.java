package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * ProcurePal's own goods revenue, split by catalog category.
 *
 * <p>A revenue split, not a demand signal. That distinction is why this narrowed to
 * ProcurePal's own sales in M6 rather than staying marketplace-wide: {@code totalRevenue}
 * below is a money figure on the operator's own page, and an unscoped one would be the
 * exact number the stakeholder objected to. Cross-seller category demand, if it is ever
 * wanted, is a super admin view and belongs next to the cross-seller revenue there.
 *
 * {@code totalRevenue} is echoed alongside the slices so the client can render "₦X of ₦Y"
 * without re-summing, and so a reader can see immediately that the shares add to one.
 * It equals the summary's merchandiseRevenue, NOT its grossRevenue - see
 * {@link CategoryMixEntry#revenue}.
 */
public record CategoryMixResponse(BigDecimal totalRevenue, List<CategoryMixEntry> categories) {
}
