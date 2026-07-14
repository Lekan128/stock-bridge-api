package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateClientStatusRequest(@NotNull Boolean active) {
}
