package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Never carries passwordHash. */
public record UserSummaryResponse(UUID id, String username, String role, boolean active, OffsetDateTime createdAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(), user.getUsername(), user.getRole().getName(), user.isActive(), user.getCreatedAt());
    }
}
