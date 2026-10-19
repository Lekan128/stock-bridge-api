package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
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
 */
public record SellingSettingsResponse(
        boolean sellsStock,
        boolean available,
        OffsetDateTime changedAt,
        String changedByName,
        SettingChangeSource changedVia) {
}
