package com.procurepal_services.stock_bridge_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Deliberately has no `root` field: root is an ownership fact established at
 * signup, never something an admin can hand out or claim through this API.
 * The profile fields are optional - an admin onboarding someone may only know
 * a username and a starting password, and the user fills in the rest
 * themselves via PUT /api/me.
 */
public record CreateUserRequest(
        @NotBlank String username,
        // Length-only for now; tighten later once there's a product decision on
        // password policy (see ClientSignupRequest for the same note).
        @NotBlank @Size(min = 8) String password,
        @NotBlank String role,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 100) String jobTitle) {
}
