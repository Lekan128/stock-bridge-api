package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.Role;
import java.util.List;

/**
 * An assignable role as the users screen needs to render it: the code to send
 * back in a create/update request, prose for the option's description, and the
 * permissions so the UI can explain what picking it grants.
 */
public record RoleResponse(String name, String description, List<String> permissions) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getName(),
                role.getDescription(),
                role.getPermissions().stream().map(Permission::getCode).sorted().toList());
    }
}
