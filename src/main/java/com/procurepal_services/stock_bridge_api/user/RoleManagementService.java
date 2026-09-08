package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.repository.PermissionRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateRoleRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create/update/delete for a tenant's own custom roles. System roles (OWNER,
 * PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER, STOREKEEPER) are
 * deliberately out of reach here - see Role's javadoc for why - so every write
 * in this class goes through RoleRepository's clientId-scoped finders, which
 * can never resolve to a system role (clientId NULL) in the first place.
 */
@Service
@RequiredArgsConstructor
public class RoleManagementService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    @Transactional
    public RoleResponse create(CreateRoleRequest request) {
        UUID tenantId = requireTenantId();
        String name = request.name().trim();

        if (roleRepository.existsByClientIdAndNameIgnoreCase(tenantId, name)) {
            throw new RoleNameTakenException(name);
        }

        Set<Permission> permissions = resolvePermissions(request.permissionCodes());

        Role role = roleRepository.save(Role.builder()
                .clientId(tenantId)
                .name(name)
                .description(normalize(request.description()))
                .permissions(permissions)
                .build());

        return RoleResponse.from(role);
    }

    @Transactional
    public RoleResponse update(UUID id, UpdateRoleRequest request) {
        UUID tenantId = requireTenantId();
        Role role = findOwnedRoleOrThrow(id, tenantId);
        String name = request.name().trim();

        if (!name.equalsIgnoreCase(role.getName())
                && roleRepository.existsByClientIdAndNameIgnoreCase(tenantId, name)) {
            throw new RoleNameTakenException(name);
        }

        role.setName(name);
        role.setDescription(normalize(request.description()));
        role.setPermissions(resolvePermissions(request.permissionCodes()));

        return RoleResponse.from(role);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = requireTenantId();
        Role role = findOwnedRoleOrThrow(id, tenantId);

        long usersOnRole = userRepository.countByClientIdAndRole_Id(tenantId, role.getId());
        if (usersOnRole > 0) {
            throw new RoleInUseException(usersOnRole);
        }

        roleRepository.delete(role);
    }

    /**
     * clientId-scoped lookup misses on a system role by construction (its clientId is always
     * NULL), so a second lookup distinguishes "doesn't exist" from "exists, but is a system
     * role" purely for a clearer error message - it grants no additional access.
     */
    private Role findOwnedRoleOrThrow(UUID id, UUID tenantId) {
        return roleRepository.findByIdAndClientId(id, tenantId).orElseGet(() -> {
            if (roleRepository.existsById(id)) {
                throw new SystemRoleNotEditableException();
            }
            throw new RoleNotFoundException();
        });
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new HashSet<>();
        }
        List<Permission> found = permissionRepository.findByCodeIn(List.copyOf(codes));
        if (found.size() != codes.size()) {
            Set<String> unknown = new HashSet<>(codes);
            found.forEach(p -> unknown.remove(p.getCode()));
            throw new InvalidPermissionCodeException(unknown);
        }
        return new HashSet<>(found);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
