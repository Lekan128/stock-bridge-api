package com.procurepal_services.stock_bridge_api.client.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientSignupRequest(
        @NotBlank String name,
        // Optional - auto-suggested from name (lowercased/hyphenated) if blank.
        String clientIdentifier,
        // Signup's admin username is specifically required to be an email, even
        // though usernames in general aren't (see users.username in the schema).
        @NotBlank @Email String adminEmail,
        // Length-only for now; tighten later (mixed case/digits/symbols) once
        // there's a product decision on password policy.
        @NotBlank @Size(min = 8) String password,
        @NotBlank String confirmPassword) {
}
