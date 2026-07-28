package com.procurepal_services.stock_bridge_api.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * The user half of a login response. platformOwner mirrors the same-named JWT
 * claim - the frontend reads it from here on login and from /api/me on reload, and
 * the two must never disagree, which is why both are built from the same Client
 * row rather than derived independently.
 */
public record TenantUserSummary(
        UUID id,
        String username,
        String role,
        List<String> permissions,
        String clientName,
        String clientIdentifier,
        boolean platformOwner) {
}
