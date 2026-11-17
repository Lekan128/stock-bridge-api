package com.procurepal_services.stock_bridge_api.selling.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Sell things you make" on or off (V90, selling till Step 4). {@code reason} is required from support - the
 * owner is told who changed it and why - and ignored from the owner.
 */
public record MadeHereSettingRequest(@NotNull Boolean on, @Size(max = 500) String reason) {
}
