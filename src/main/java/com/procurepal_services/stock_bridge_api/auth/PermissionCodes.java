package com.procurepal_services.stock_bridge_api.auth;

import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.User;
import java.util.List;

/**
 * The single definition of "what can this user do", derived from their role's
 * permissions. Lives here because the login response was the first thing to
 * need it, but /api/me returns the same list and must never drift from what
 * the access token was minted with - so both go through this, rather than each
 * re-walking the role graph its own way.
 *
 * Sorted for a stable, comparable list (tokens, responses, and test
 * assertions all benefit from a deterministic order).
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    public static List<String> of(User user) {
        return user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();
    }
}
