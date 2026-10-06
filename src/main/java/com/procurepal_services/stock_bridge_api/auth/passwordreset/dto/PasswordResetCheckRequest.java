package com.procurepal_services.stock_bridge_api.auth.passwordreset.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetCheckRequest(@NotBlank String token) {
}
