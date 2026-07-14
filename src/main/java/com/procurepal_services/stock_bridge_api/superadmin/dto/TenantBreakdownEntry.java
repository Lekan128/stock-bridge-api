package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One row of the per-tenant breakdown inside PlatformAggregateResponse. */
public record TenantBreakdownEntry(
        UUID clientId,
        String clientName,
        boolean active,
        long activeUserCount,
        long activeProductCount,
        BigDecimal stockInValue,
        BigDecimal stockOutValue) {
}
