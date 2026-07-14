package com.procurepal_services.stock_bridge_api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SuperAdminLoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
