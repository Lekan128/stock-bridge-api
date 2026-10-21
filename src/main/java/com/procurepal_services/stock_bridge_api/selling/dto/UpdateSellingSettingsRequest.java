package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import com.procurepal_services.stock_bridge_api.entity.StockUse;
import jakarta.validation.constraints.NotNull;

/**
 * @param sellsStock the new value.
 * @param stockUse the first-week answer (V49), when this change is that answer - "We use it" sends
 *     USE with {@code sellsStock} false, so it is remembered without changing anything else.
 * @param source where in the app it was changed - {@code OWNER} (the settings page, and the default
 *     when absent), {@code FIRST_WEEK} or {@code IN_CONTEXT}. {@code SUPPORT} is refused here: only the
 *     support endpoint may claim it.
 */
public record UpdateSellingSettingsRequest(@NotNull Boolean sellsStock, SettingChangeSource source, StockUse stockUse) {

    /** The shape before the first-week answer (V49). */
    public UpdateSellingSettingsRequest(Boolean sellsStock, SettingChangeSource source) {
        this(sellsStock, source, null);
    }
}
