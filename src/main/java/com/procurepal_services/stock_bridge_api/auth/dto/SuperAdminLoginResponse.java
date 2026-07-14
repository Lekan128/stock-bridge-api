package com.procurepal_services.stock_bridge_api.auth.dto;

public record SuperAdminLoginResponse(AuthTokens tokens, SuperAdminSummary admin) {
}
