package com.procurepal_services.stock_bridge_api.profile.dto;

import com.procurepal_services.stock_bridge_api.auth.PermissionCodes;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the app needs about "who am I", in one call - identity, profile,
 * and effective permissions. permissions is built by the same PermissionCodes
 * helper that mints the access token's claims, so a screen gating on this list
 * can never disagree with what the API will actually authorize.
 *
 * Never carries passwordHash.
 */
public record ProfileResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String email,
        String phone,
        String jobTitle,
        String role,
        List<String> permissions,
        boolean root,
        boolean active,
        OffsetDateTime createdAt,
        String clientName,
        String clientIdentifier) {

    public static ProfileResponse from(User user, Client client) {
        return new ProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getJobTitle(),
                user.getRole().getName(),
                PermissionCodes.of(user),
                user.isRoot(),
                user.isActive(),
                user.getCreatedAt(),
                client.getName(),
                client.getSlug());
    }
}
