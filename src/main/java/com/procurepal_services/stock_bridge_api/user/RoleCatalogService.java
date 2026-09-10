package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the assignable roles for the current tenant: every system role plus, since V27, that
 * tenant's own custom roles. Roles are otherwise the backend's to define - a frontend that
 * renders whatever this returns needs no change as tenants create more custom roles.
 *
 * <h2>Assignable, not "every row in the table"</h2>
 * Since V11 the roles table also holds VENDOR, which a marketplace seller's single account
 * carries and which nobody may be given through user management - see {@link TenantRoles#VENDOR}.
 * This endpoint feeds a role picker, so it serves {@link TenantRoles#ALL} among the system roles:
 * offering an option that the very next request rejects with a 400 is worse than not offering it.
 * A tenant's own custom roles are always assignable - nobody but that tenant can see them.
 */
@Service
@RequiredArgsConstructor
public class RoleCatalogService {

    private final RoleRepository roleRepository;

    /** System roles first, then this tenant's custom roles, each group sorted by name. */
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        UUID tenantId = TenantContext.get();
        return roleRepository.findByClientIdIsNullOrClientId(tenantId).stream()
                .filter(role -> !role.isSystem() || TenantRoles.ALL.contains(role.getName()))
                .sorted(Comparator.<Role, Boolean>comparing(role -> !role.isSystem())
                        .thenComparing(Role::getName))
                .map(RoleResponse::from)
                .toList();
    }
}
