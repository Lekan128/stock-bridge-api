package com.procurepal_services.stock_bridge_api.profile.dto;

import com.procurepal_services.stock_bridge_api.auth.PermissionCodes;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
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
 * emailVerified and receivePromotionalEmail are read-only here on purpose, and the
 * asymmetry between them is worth knowing. emailVerified has exactly one writer in
 * the whole application - redeeming a token at POST /api/email/verify - because a
 * field the holder of an account can set for themselves is not evidence about an
 * inbox, it is just a preference with a misleading name. receivePromotionalEmail
 * IS writable, but through PUT /api/me/email-preferences, which is module C's
 * endpoint; it is surfaced here so the profile screen can render its current state
 * without a second call, not so it can be written through PUT /api/me. Neither
 * appears in UpdateProfileRequest, and neither should.
 *
 * platformOwner is here for the same reason and with the same discipline: it is
 * read straight off the Client row, exactly like the JWT's platformOwner claim and
 * the login response's TenantUserSummary.platformOwner, so a page reload can never
 * decide differently from a fresh login about whether to show the marketplace-admin
 * nav.
 *
 * clientType is the same arrangement for the vendor half of the product, added by
 * V11: same value, same three places (JWT claim, login response, here), serialized
 * as the enum's name - "COMPANY" or "VENDOR". It is ORTHOGONAL to platformOwner,
 * not an alternative to it: ProcurePal is a COMPANY that owns the platform. Like
 * platformOwner it decides what the app RENDERS and never what the API allows -
 * VendorGuard re-reads the clients row per request, so a change of kind takes
 * effect on the next call rather than at the next token expiry.
 *
 * Never carries passwordHash.
 */
public record ProfileResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String email,
        boolean emailVerified,
        boolean receivePromotionalEmail,
        String phone,
        String jobTitle,
        String role,
        boolean roleIsSystem,
        List<String> permissions,
        boolean root,
        boolean active,
        OffsetDateTime createdAt,
        String clientName,
        String clientIdentifier,
        boolean platformOwner,
        ClientType clientType) {

    public static ProfileResponse from(User user, Client client) {
        return new ProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.isEmailVerified(),
                user.isReceivePromotionalEmail(),
                user.getPhone(),
                user.getJobTitle(),
                user.getRole().getName(),
                user.getRole().isSystem(),
                PermissionCodes.of(user),
                user.isRoot(),
                user.isActive(),
                user.getCreatedAt(),
                client.getName(),
                client.getSlug(),
                client.isPlatformOwner(),
                ClientType.orDefault(client.getClientType()));
    }
}
