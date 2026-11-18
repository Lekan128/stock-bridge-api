package com.procurepal_services.stock_bridge_api.selling.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** How long after a close a drawer can be opened again (V98, selling till Step 13): 1 to 24 hours. */
public record CashReopenHoursRequest(
        @NotNull @Min(value = 1, message = "At least 1 hour.") @Max(value = 24, message = "At most 24 hours.") Integer hours) {
}
