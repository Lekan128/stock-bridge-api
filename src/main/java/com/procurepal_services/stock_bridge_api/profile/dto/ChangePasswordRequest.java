package com.procurepal_services.stock_bridge_api.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * currentPassword is what makes this safe to expose to every user, root
 * included: proving possession of the existing password is what an
 * admin-initiated reset can't do (see UserManagementService.resetPassword).
 * The min length matches the rule the signup and create-user forms already
 * enforce - one password policy, three entry points.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8) String newPassword,
        @NotBlank String confirmNewPassword) {
}
