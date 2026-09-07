package com.procurepal_services.stock_bridge_api.user.dto;

import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.Role;
import java.util.List;
import java.util.UUID;

/**
 * An assignable role as the users and Roles & Privileges screens need to render it: the id to
 * send back in a create/update request, prose for the option's description, the permissions so
 * the UI can explain what picking it grants, and isSystem so the UI can lock OWNER/PROCUREMENT_
 * MANAGER/INVENTORY_OFFICER/FINANCE_OFFICER/STOREKEEPER against edit/delete (see Role's javadoc).
 */
public record RoleResponse(UUID id, String name, String description, List<String> permissions, boolean isSystem) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.getPermissions().stream().map(Permission::getCode).sorted().toList(),
                role.isSystem());
    }
}
