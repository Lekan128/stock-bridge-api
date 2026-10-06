package com.procurepal_services.stock_bridge_api.client.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Self-service signup.
 *
 * <p>Since the landing page's step 4 (LANDING_PAGE_PLAN.md §4) a shop signs up with a business
 * name, its WhatsApp number and a password: an email is optional, and the owner then logs in with
 * the phone number. One of the two is required, checked in ClientSignupService because it spans two
 * fields. The older shape (email, confirmed password) still works unchanged.
 */
public record ClientSignupRequest(
        @NotBlank String name,
        // Optional. Blank means generate one from the name (with -2, -3... if that is taken).
        String clientIdentifier,
        // Optional since step 4. When present it is the owner's username, as before.
        @Email String adminEmail,
        // Length-only for now; tighten later (mixed case/digits/symbols) once
        // there's a product decision on password policy.
        @NotBlank @Size(min = 8) String password,
        // Optional since step 4 (the form shows the password instead of asking twice). When
        // present it must match.
        String confirmPassword,
        // The shop's WhatsApp number. Required when there is no email, and then it must be a
        // Nigerian mobile number: it becomes the owner's username, in +234 form.
        @Size(max = 50) String phone,
        // The landing page's setup request this signup follows, from "Create your password"
        // (?setup=). Optional.
        UUID setupRequestId) {

    /**
     * The five-argument form, kept so the (many) existing callers and tests that
     * predate the phone field still compile and still mean the same thing. A record
     * with an appended optional component would otherwise break every construction
     * site for a field none of them supply.
     */
    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, null, null);
    }

    /** The six-argument form, from before signup could follow a setup request. */
    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword,
            String phone) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, phone, null);
    }
}
