package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.Permission;

/** One row of the full privilege catalog - GET /api/permissions, what the Roles & Privileges matrix renders. */
public record PermissionResponse(String code, String description) {

    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(permission.getCode(), permission.getDescription());
    }
}
