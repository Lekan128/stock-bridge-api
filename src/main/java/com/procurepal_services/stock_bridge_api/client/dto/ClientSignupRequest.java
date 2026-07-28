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
        @NotBlank String confirmPassword,
        // Optional company phone. Length-only validation deliberately: Nigerian
        // numbers get typed as 0803..., +234 803..., with spaces and with dashes,
        // and rejecting a signup over a phone format would be a self-inflicted
        // wound. Normalising it is a later concern.
        @Size(max = 50) String phone) {

    /**
     * The five-argument form, kept so the (many) existing callers and tests that
     * predate the phone field still compile and still mean the same thing. A record
     * with an appended optional component would otherwise break every construction
     * site for a field none of them supply.
     */
    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, null);
    }
}
