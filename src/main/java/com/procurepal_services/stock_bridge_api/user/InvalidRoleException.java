package com.procurepal_services.stock_bridge_api.user;

import java.util.UUID;

public class InvalidRoleException extends RuntimeException {

    /** Tenant-facing surface, since V27 - role resolution is by id, not name. */
    public InvalidRoleException(UUID roleId) {
        super("Role '" + roleId + "' is not a system role or a role owned by this organization.");
    }

    /** SuperAdminUserService's platform-owner surface - still name-based, see PlatformOwnerCreateUserRequest. */
    public InvalidRoleException(String roleName) {
        super("Invalid role '" + roleName + "'. Must be one of "
                + "OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER, STOREKEEPER.");
    }
}
