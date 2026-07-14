package com.procurepal_services.stock_bridge_api.auth.dto;

import java.util.List;
import java.util.UUID;

public record TenantUserSummary(
        UUID id, String username, String role, List<String> permissions, String clientName, String clientIdentifier) {
}
