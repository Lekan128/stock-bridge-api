package com.procurepal_services.stock_bridge_api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank String username,
        // Length-only for now; tighten later once there's a product decision on
        // password policy (see ClientSignupRequest for the same note).
        @NotBlank @Size(min = 8) String password,
        @NotBlank String role) {
}
