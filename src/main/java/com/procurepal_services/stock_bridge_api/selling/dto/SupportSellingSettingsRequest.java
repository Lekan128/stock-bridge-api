package com.procurepal_services.stock_bridge_api.selling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Support changing a company's selling switch: always with a reason, which the owner is shown. */
public record SupportSellingSettingsRequest(
        @NotNull Boolean sellsStock,
        @NotBlank(message = "Say why you are changing this - the owner is told.") @Size(max = 500) String reason) {
}
