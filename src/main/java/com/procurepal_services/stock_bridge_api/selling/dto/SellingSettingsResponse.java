package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import com.procurepal_services.stock_bridge_api.entity.StockUse;
import java.time.OffsetDateTime;

/**
 * The company's selling switch, for the settings page and the session profile.
 *
 * @param sellsStock whether the company sells from its stock.
 * @param available false for a marketplace vendor, which cannot choose it (plan decision 6) - the
 *     page shows no switch rather than one that would be refused.
 * @param changedAt when it was last changed; absent if never.
 * @param changedByName who changed it, as the page says it: the person's name, or "ProcurePal support"
 *     - never a support account's own username.
 * @param changedVia which path it was changed through.
 * @param stockUse the first-week answer (V49); absent until answered - the card asks while it is.
 * @param legacyPriceCount active products whose only price is an old one V44 set aside, waiting to be
 *     reviewed (section 4.6.3). Zero once each has a selling price or there were none.
 * @param legacyPricesDismissed the owner said "not now" to that review; the prices are still kept.
 */
public record SellingSettingsResponse(
        boolean sellsStock,
        boolean available,
        OffsetDateTime changedAt,
        String changedByName,
        SettingChangeSource changedVia,
        StockUse stockUse,
        long legacyPriceCount,
        boolean legacyPricesDismissed) {
}
