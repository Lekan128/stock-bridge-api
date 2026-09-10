package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The role catalogue (system roles + the tenant's own custom roles) and, since V27, management
 * of custom roles - see RoleManagementService for why system roles are out of reach here.
 *
 * GET stays available to MANAGE_USERS as well as MANAGE_ROLES: it exists to populate the role
 * picker on the users screen too, so whoever can assign a role can list them.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleCatalogService roleCatalogService;
    private final RoleManagementService roleManagementService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_USERS', 'MANAGE_ROLES')")
    public List<RoleResponse> list() {
        return roleCatalogService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    public RoleResponse create(@Valid @RequestBody CreateRoleRequest request) {
        return roleManagementService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    public RoleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleManagementService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    public void delete(@PathVariable UUID id) {
        roleManagementService.delete(id);
    }
}
