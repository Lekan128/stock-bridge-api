package com.procurepal_services.stock_bridge_api.client.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Self-service signup.
 *
 * <p>A shop signs up with a business name, an email, its WhatsApp number and a password. The email
 * is required (the owners' decision of 2026-10-06: it is how Procurepaddy writes to the company) and
 * is the owner's username; the WhatsApp number is how the setup team reaches them, and the owner can
 * log in with it too, typed any way (AuthService). The older shape (no phone, a confirmed password)
 * still works unchanged.
 */
public record ClientSignupRequest(
        @NotBlank(message = "Enter your business name.") String name,
        // Optional. Blank means generate one from the name (with -2, -3... if that is taken).
        String clientIdentifier,
        // Required: the owner's username, and where Procurepaddy writes to the company.
        @NotBlank(message = "Enter your email address.") @Email(message = "Enter a valid email address.") String adminEmail,
        // Length-only for now; tighten later (mixed case/digits/symbols) once
        // there's a product decision on password policy.
        @NotBlank(message = "Enter a password.") @Size(min = 8, message = "Use at least 8 characters for the password.") String password,
        // Optional since step 4 (the form shows the password instead of asking twice). When
        // present it must match.
        String confirmPassword,
        // The shop's WhatsApp number. Optional here (older callers send none; the sign-up form
        // requires it). A Nigerian mobile number is stored in +234 form and logs the owner in too.
        @Size(max = 50, message = "That phone number is too long.") String phone,
        // The landing page's setup request this signup follows, from "Create your password"
        // (?setup=). Optional.
        UUID setupRequestId,
        // Honeypot. The sign-up form renders this field hidden from people (off-screen, not
        // focusable, autocomplete off), so a human never fills it and a naive form-filling bot
        // does. Anything here means the request is refused. Deliberately named like a real field.
        @Size(max = 500) String website) {

    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword,
            String phone, UUID setupRequestId) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, phone, setupRequestId, null);
    }

    /**
     * The five-argument form, kept so the (many) existing callers and tests that
     * predate the phone field still compile and still mean the same thing. A record
     * with an appended optional component would otherwise break every construction
     * site for a field none of them supply.
     */
    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, null, null, null);
    }

    /** The six-argument form, from before signup could follow a setup request. */
    public ClientSignupRequest(
            String name, String clientIdentifier, String adminEmail, String password, String confirmPassword,
            String phone) {
        this(name, clientIdentifier, adminEmail, password, confirmPassword, phone, null, null);
    }
}
