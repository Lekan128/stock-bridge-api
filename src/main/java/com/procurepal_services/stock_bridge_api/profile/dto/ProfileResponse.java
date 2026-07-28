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
 * platformOwner is here for the same reason and with the same discipline: it is
 * read straight off the Client row, exactly like the JWT's platformOwner claim and
 * the login response's TenantUserSummary.platformOwner, so a page reload can never
 * decide differently from a fresh login about whether to show the marketplace-admin
 * nav.
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
        String clientIdentifier,
        boolean platformOwner) {

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
                client.getSlug(),
                client.isPlatformOwner());
    }
}
