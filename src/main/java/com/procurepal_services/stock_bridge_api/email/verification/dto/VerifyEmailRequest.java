package com.procurepal_services.stock_bridge_api.email.verification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/email/verify}. One field, and it is a bearer
 * credential.
 *
 * <h2>Why the token is in the body and not the URL</h2>
 * The frontend receives it as a query parameter ({@code /verify-email?token=...} -
 * that is a link in an email, and a link has nowhere else to put it) and then
 * POSTs it here. Carrying it onward as a query string would write a live
 * credential into this API's access logs, into any proxy in front of it, and into
 * the {@code Referer} header of every request the confirmation page subsequently
 * makes. A request body is logged by none of those.
 *
 * <p>POST rather than GET for a second reason: mail clients and security scanners
 * prefetch links. A GET endpoint that consumed the token would be redeemed by
 * Outlook's link checker before the human ever clicked, and the user would meet a
 * dead link on their first and only attempt. The frontend route being a GET is
 * fine because it does nothing but render a page and call this.
 *
 * <h2>The size bound</h2>
 * A real token is 86 characters. The cap is generous enough to survive a change of
 * encoding and tight enough that this endpoint - which is unauthenticated and
 * therefore reachable by anyone - cannot be made to SHA-256 a multi-megabyte
 * string. Validation rejects it before any work happens.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "A verification token is required")
        @Size(max = 512, message = "That is not a valid verification token")
        String token) {
}
