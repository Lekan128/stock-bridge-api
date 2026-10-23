package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.time.OffsetDateTime;
import java.util.List;
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
        OffsetDateTime createdAt,
        // Multiple branches (V52): the open branches they record at now - their own, the main branch for
        // somebody placed nowhere, every one when their role works everywhere (worksEverywhere). Absent
        // where nobody asked (the super-admin screens).
        List<UUID> branchIds,
        Boolean worksEverywhere) {

    /** Without branches - the super-admin screens, which have no branch interface. */
    public static UserSummaryResponse from(User user) {
        return from(user, null, null);
    }

    public static UserSummaryResponse from(User user, List<UUID> branchIds, Boolean worksEverywhere) {
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
                user.getCreatedAt(),
                branchIds,
                worksEverywhere);
    }
}
