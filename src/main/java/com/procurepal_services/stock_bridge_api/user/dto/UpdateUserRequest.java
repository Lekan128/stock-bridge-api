package com.procurepal_services.stock_bridge_api.user.dto;

/** Both fields optional/nullable - only the ones provided are changed. */
public record UpdateUserRequest(String role, Boolean active) {
}
