package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All reads/writes here go through TenantScopedRepository's *ForCurrentTenant
 * methods (or an explicit client_id from TenantContext), so a caller can
 * never see or touch another tenant's users - enforced at the query, not by
 * filtering a response afterward.
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private static final String ADMIN = "ADMIN";
    private static final Set<String> VALID_ROLE_NAMES = Set.of("ADMIN", "MANAGER", "STAFF");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> list(Pageable pageable) {
        return userRepository.findAllForCurrentTenant(pageable).map(UserSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse get(UUID id) {
        return UserSummaryResponse.from(findTenantUserOrThrow(id));
    }

    @Transactional
    public UserSummaryResponse create(CreateUserRequest request) {
        UUID tenantId = requireTenantId();

        if (userRepository.findByClientIdAndUsername(tenantId, request.username()).isPresent()) {
            throw new UsernameTakenException(request.username());
        }

        Role role = resolveRole(request.role());

        User user = userRepository.save(User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                .active(true)
                .build());

        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse update(UUID id, UpdateUserRequest request, UUID callerId) {
        User user = findTenantUserOrThrow(id);

        boolean requestsChange = request.role() != null || request.active() != null;
        if (requestsChange && user.getId().equals(callerId)) {
            throw new SelfServiceNotAllowedException();
        }

        Role newRole = request.role() != null ? resolveRole(request.role()) : user.getRole();
        boolean newActive = request.active() != null ? request.active() : user.isActive();

        assertKeepsAtLeastOneActiveAdmin(user, newRole.getName(), newActive);

        user.setRole(newRole);
        user.setActive(newActive);
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new PasswordMismatchException();
        }
        User user = findTenantUserOrThrow(id);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional
    public void deactivate(UUID id, UUID callerId) {
        User user = findTenantUserOrThrow(id);

        if (user.getId().equals(callerId)) {
            throw new SelfServiceNotAllowedException();
        }

        assertKeepsAtLeastOneActiveAdmin(user, user.getRole().getName(), false);
        user.setActive(false);
    }

    private User findTenantUserOrThrow(UUID id) {
        return userRepository.findByIdForCurrentTenant(id).orElseThrow(UserNotFoundException::new);
    }

    private Role resolveRole(String roleName) {
        if (!VALID_ROLE_NAMES.contains(roleName)) {
            throw new InvalidRoleException(roleName);
        }
        return roleRepository.findByName(roleName)
                .orElseThrow(
                        () -> new IllegalStateException(roleName + " role not seeded - run the Flyway migrations"));
    }

    /**
     * Blocks any change that would take the target user from "active admin" to
     * not, if they're currently the tenant's last one. Covers role changes away
     * from ADMIN and deactivation uniformly, since both are just different ways
     * to lose the tenant's last active admin.
     */
    private void assertKeepsAtLeastOneActiveAdmin(User target, String newRoleName, boolean newActive) {
        boolean wasActiveAdmin = ADMIN.equals(target.getRole().getName()) && target.isActive();
        boolean staysActiveAdmin = ADMIN.equals(newRoleName) && newActive;
        if (wasActiveAdmin && !staysActiveAdmin) {
            long activeAdmins = userRepository.countByClientIdAndRole_NameAndActiveTrue(target.getClientId(), ADMIN);
            if (activeAdmins <= 1) {
                throw new LastActiveAdminException();
            }
        }
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
