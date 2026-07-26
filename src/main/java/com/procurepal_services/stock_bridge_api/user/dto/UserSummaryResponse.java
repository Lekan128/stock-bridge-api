package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Never carries passwordHash. */
public record UserSummaryResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        String jobTitle,
        String role,
        boolean root,
        boolean active,
        OffsetDateTime createdAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getJobTitle(),
                user.getRole().getName(),
                user.isRoot(),
                user.isActive(),
                user.getCreatedAt());
    }
}
