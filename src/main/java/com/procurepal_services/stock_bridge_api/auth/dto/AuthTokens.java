package com.procurepal_services.stock_bridge_api.auth.dto;

public record AuthTokens(String accessToken, String refreshToken, long expiresInSeconds) {
}
