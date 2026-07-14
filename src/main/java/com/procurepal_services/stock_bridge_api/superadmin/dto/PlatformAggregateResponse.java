package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/superadmin/analytics/aggregate. The top-level totals sum across
 * every client (active or suspended) for the given date range - see
 * SuperAdminAggregateService for how each figure is derived from the same
 * per-client aggregation AnalyticsService uses for a tenant's own analytics.
 */
public record PlatformAggregateResponse(
        long totalActiveClients,
        long totalActiveUsers,
        long totalActiveProducts,
        BigDecimal totalStockInValue,
        BigDecimal totalStockOutValue,
        List<TenantBreakdownEntry> tenantBreakdown) {
}
