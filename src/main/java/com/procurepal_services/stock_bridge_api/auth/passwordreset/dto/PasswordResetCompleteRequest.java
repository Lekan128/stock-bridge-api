package com.procurepal_services.stock_bridge_api.auth.passwordreset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The 8-character minimum is the same rule signup, change-password and admin reset use. */
public record PasswordResetCompleteRequest(@NotBlank String token, @NotBlank @Size(min = 8) String newPassword) {
}
