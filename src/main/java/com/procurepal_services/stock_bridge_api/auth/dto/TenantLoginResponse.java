package com.procurepal_services.stock_bridge_api.auth.dto;

public record TenantLoginResponse(AuthTokens tokens, TenantUserSummary user) {
}
