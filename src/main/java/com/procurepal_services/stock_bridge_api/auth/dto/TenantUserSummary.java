package com.procurepal_services.stock_bridge_api.auth.dto;

import com.procurepal_services.stock_bridge_api.entity.ClientType;
import java.util.List;
import java.util.UUID;

/**
 * The user half of a login response. platformOwner and clientType each mirror the
 * same-named JWT claim - the frontend reads them from here on login and from
 * /api/me on reload, and the three must never disagree, which is why all of them
 * are built from the same Client row rather than derived independently. See
 * PermissionCodes for the same discipline applied to the permission list.
 *
 * clientType serializes as the enum's name, so "COMPANY" or "VENDOR". It is
 * ORTHOGONAL to platformOwner: ProcurePal is "COMPANY" with platformOwner true,
 * because it buys and has staff as well as selling. A frontend that reads "not the
 * platform owner" as "an ordinary buyer" was right before this field existed and
 * is wrong now.
 *
 * Both are for deciding what the app RENDERS - which nav groups exist, which route
 * guards pass - and never for deciding what the API will allow. VendorGuard and
 * PlatformOwnerGuard re-read the clients row on every request, so a client whose
 * type or flag changed is treated correctly by the server on the very next call
 * rather than whenever the holder's 15-minute access token expires. That is also
 * the answer to "why not just read it off the token": you may, for rendering.
 */
public record TenantUserSummary(
        UUID id,
        String username,
        String role,
        List<String> permissions,
        String clientName,
        String clientIdentifier,
        boolean platformOwner,
        ClientType clientType) {
}
