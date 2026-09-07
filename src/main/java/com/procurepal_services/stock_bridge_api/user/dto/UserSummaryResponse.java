package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Never carries passwordHash. Carries the role as an id (what a PUT back to this user must send
 * - see UpdateUserRequest) plus its name and isSystem for display, rather than making every
 * caller resolve the id through GET /api/roles just to show a label.
 */
public record UserSummaryResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        String jobTitle,
        UUID roleId,
        String roleName,
        boolean roleIsSystem,
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
                user.getRole().getId(),
                user.getRole().getName(),
                user.getRole().isSystem(),
                user.isRoot(),
                user.isActive(),
                user.getCreatedAt());
    }
}
