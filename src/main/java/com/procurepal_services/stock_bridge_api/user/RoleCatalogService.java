package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the assignable roles from the roles table rather than a compiled-in
 * list. Roles are global (no client_id - see the roles table in V1), so there
 * is nothing tenant-scoped to isolate here; the point of reading them from the
 * database is that the product intends to grow tenant-defined roles, and a
 * frontend that renders whatever the API returns needs no change when that
 * lands.
 *
 * <h2>Assignable, not "every row in the table"</h2>
 * Since V11 the roles table also holds VENDOR, which a marketplace seller's
 * single account carries and which nobody may be given through user management -
 * see {@link TenantRoles#VENDOR}. This endpoint feeds a role picker, so it serves
 * {@link TenantRoles#ALL}: offering an option that the very next request rejects
 * with a 400 is worse than not offering it.
 *
 * <p>The filter is applied here rather than by asking the database for specific
 * names, so the tenant-defined-roles direction above still works - a future
 * tenant role is added to the allow-list, not to a hardcoded query.
 */
@Service
@RequiredArgsConstructor
public class RoleCatalogService {

    private final RoleRepository roleRepository;

    /** Sorted by name so the options render in a stable order across calls. */
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roleRepository.findAll().stream()
                .filter(role -> TenantRoles.ALL.contains(role.getName()))
                .sorted(Comparator.comparing(role -> role.getName()))
                .map(RoleResponse::from)
                .toList();
    }
}
