package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import jakarta.validation.constraints.NotNull;

/**
 * @param sellsStock the new value.
 * @param source where in the app it was changed - {@code OWNER} (the settings page, and the default
 *     when absent), {@code FIRST_WEEK} or {@code IN_CONTEXT}. {@code SUPPORT} is refused here: only the
 *     support endpoint may claim it.
 */
public record UpdateSellingSettingsRequest(@NotNull Boolean sellsStock, SettingChangeSource source) {
}
