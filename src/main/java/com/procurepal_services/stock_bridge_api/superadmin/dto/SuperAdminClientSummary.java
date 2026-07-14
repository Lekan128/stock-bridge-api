package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of GET /api/superadmin/clients. adminEmail doubles as the admin's
 * login username - ClientSignupService creates the first user with
 * username == adminContactEmail, so there's no separate lookup for it.
 */
public record SuperAdminClientSummary(
        UUID id,
        String name,
        String slug,
        boolean active,
        String adminEmail,
        long userCount,
        long productCount,
        OffsetDateTime createdAt) {
}
