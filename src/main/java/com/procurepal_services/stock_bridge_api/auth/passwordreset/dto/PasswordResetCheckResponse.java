package com.procurepal_services.stock_bridge_api.auth.passwordreset.dto;

/**
 * Which account a valid link resets, so the page can say "For ada@... at Mama Tee
 * Stores" before anyone types a password. Only shown to someone holding the link,
 * which already proves they read the inbox it was sent to.
 */
public record PasswordResetCheckResponse(String companyName, String clientIdentifier, String login) {
}
