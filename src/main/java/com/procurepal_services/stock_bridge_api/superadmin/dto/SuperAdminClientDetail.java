package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GET /api/superadmin/clients/{id}. activeUserCount/activeProductCount/
 * lowStockProductCount are the "quick-glance" numbers, distinct from the
 * plain userCount/productCount totals also shown in the list view.
 */
public record SuperAdminClientDetail(
        UUID id,
        String name,
        String slug,
        boolean active,
        String adminEmail,
        long userCount,
        long productCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long activeUserCount,
        long activeProductCount,
        long lowStockProductCount) {
}
