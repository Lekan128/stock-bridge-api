package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.verification.EmailVerificationService;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import com.procurepal_services.stock_bridge_api.vendor.VendorSingleAccountRule;
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
 *
 * A third guard is about the tenant's KIND rather than its integrity: a VENDOR
 * client has exactly one user and cannot gain a second here. Today the VENDOR
 * role also lacks MANAGE_USERS, so no vendor can reach this method at all - but
 * that is a permission, and a permission is a decision somebody can revisit. The
 * check below is the rule itself; see {@link VendorSingleAccountRule}.
 *
 * Two independent guards keep a tenant from losing control of itself:
 * "there must always be an active OWNER" (a headcount rule, satisfiable by
 * promoting someone else first) and "the root user is untouchable" (an
 * ownership rule about one specific account). They coincide for the common
 * single-owner tenant but are not the same thing - a tenant with three owners
 * still can't demote or deactivate the account holder.
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final EmailVerificationService emailVerificationService;
    private final VendorSingleAccountRule vendorSingleAccountRule;

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

        // Before the username check and before anything is built: a refusal here must
        // leave no user row, no verification token and no invitation email behind.
        vendorSingleAccountRule.assertMayAddUser(tenantId);

        if (userRepository.findByClientIdAndUsername(tenantId, request.username()).isPresent()) {
            throw new UsernameTakenException(request.username());
        }

        Role role = resolveRole(request.roleId());

        User user = userRepository.save(User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                .active(true)
                // Stated rather than left to the column default: nothing created
                // through this API is ever the account holder.
                .root(false)
                .firstName(normalize(request.firstName()))
                .lastName(normalize(request.lastName()))
                .email(normalize(request.email()))
                .phone(normalize(request.phone()))
                .jobTitle(normalize(request.jobTitle()))
                .build());

        // Tells the new user their account exists and what to sign in as. It cannot
        // tell them the password the admin just typed - see AccountEmails for why
        // that convenience is not worth what it costs.
        //
        // It DOES carry a verification link, and a sub-user needs one at least as
        // much as an account holder does. The address on this row was typed by an
        // administrator, about somebody else, so it is the population most likely to
        // be wrong - and an unverified user receives no order mail at all under V8,
        // silently and permanently. Without this, "our warehouse lead never gets
        // delivery notifications" becomes a support ticket with no visible cause,
        // and the only escape is the user happening to find the resend button. The
        // link goes ON this email rather than in a second one for the same reason it
        // does at signup; see AccountEmails.userInvited.
        VerificationLink verificationLink = emailVerificationService.issueLink(user);
        emailNotificationService.userInvited(user, role.getName(), verificationLink);

        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse update(UUID id, UpdateUserRequest request, UUID callerId) {
        User user = findTenantUserOrThrow(id);

        // Checked before the root rules so a root user editing themselves still
        // gets the more specific "you can't change your own role here" message.
        boolean requestsChange = request.roleId() != null || request.active() != null;
        if (requestsChange && user.getId().equals(callerId)) {
            throw new SelfServiceNotAllowedException();
        }

        if (user.isRoot()) {
            if (request.roleId() != null) {
                throw new RootUserRoleChangeNotAllowedException();
            }
            if (Boolean.FALSE.equals(request.active())) {
                throw new RootUserDeactivationNotAllowedException();
            }
        }

        Role newRole = request.roleId() != null ? resolveRole(request.roleId()) : user.getRole();
        boolean newActive = request.active() != null ? request.active() : user.isActive();

        assertKeepsAtLeastOneActiveOwner(user, newRole.getName(), newActive);

        user.setRole(newRole);
        user.setActive(newActive);
        applyProfilePatch(user, request);
        return UserSummaryResponse.from(user);
    }

    /**
     * An admin-set password is a password the admin knows, so this is only ever
     * safe to point at a sub-user. The account holder changes their own through
     * POST /api/me/password, which proves possession of the current one.
     */
    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest request, UUID callerId) {
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new PasswordMismatchException();
        }
        User user = findTenantUserOrThrow(id);
        if (user.isRoot() && !user.getId().equals(callerId)) {
            throw new RootPasswordResetNotAllowedException();
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // An alert, not a delivery. Its whole value is reaching a user whose password
        // was changed without their knowledge, so it names the account and says to
        // raise it - and carries no credential of any kind.
        emailNotificationService.passwordResetByAdmin(user);
    }

    @Transactional
    public void deactivate(UUID id, UUID callerId) {
        User user = findTenantUserOrThrow(id);

        if (user.getId().equals(callerId)) {
            throw new SelfServiceNotAllowedException();
        }
        if (user.isRoot()) {
            throw new RootUserDeactivationNotAllowedException();
        }

        assertKeepsAtLeastOneActiveOwner(user, user.getRole().getName(), false);
        user.setActive(false);
    }

    private User findTenantUserOrThrow(UUID id) {
        return userRepository.findByIdForCurrentTenant(id).orElseThrow(UserNotFoundException::new);
    }

    /**
     * A role a user may be assigned: a system role from {@link TenantRoles#ALL} (VENDOR
     * excluded - see that constant), or a custom role this tenant owns (client_id V27). Neither
     * branch trusts the id alone - a role id belonging to another tenant's custom role, or to
     * VENDOR, is rejected exactly like one that doesn't exist at all.
     */
    private Role resolveRole(UUID roleId) {
        Role role = roleRepository.findById(roleId).orElseThrow(() -> new InvalidRoleException(roleId));
        boolean assignable = role.isSystem() ? TenantRoles.ALL.contains(role.getName())
                : role.getClientId().equals(requireTenantId());
        if (!assignable) {
            throw new InvalidRoleException(roleId);
        }
        return role;
    }

    /** Null means "leave alone" here, not "clear" - see UpdateUserRequest. */
    private void applyProfilePatch(User user, UpdateUserRequest request) {
        if (request.firstName() != null) {
            user.setFirstName(normalize(request.firstName()));
        }
        if (request.lastName() != null) {
            user.setLastName(normalize(request.lastName()));
        }
        if (request.email() != null) {
            user.setEmail(normalize(request.email()));
        }
        if (request.phone() != null) {
            user.setPhone(normalize(request.phone()));
        }
        if (request.jobTitle() != null) {
            user.setJobTitle(normalize(request.jobTitle()));
        }
    }

    /**
     * Blocks any change that would take the target user from "active owner" to
     * not, if they're currently the tenant's last one. Covers role changes away
     * from OWNER and deactivation uniformly, since both are just different ways
     * to lose the tenant's last active owner.
     */
    private void assertKeepsAtLeastOneActiveOwner(User target, String newRoleName, boolean newActive) {
        boolean wasActiveOwner = TenantRoles.OWNER.equals(target.getRole().getName()) && target.isActive();
        boolean staysActiveOwner = TenantRoles.OWNER.equals(newRoleName) && newActive;
        if (wasActiveOwner && !staysActiveOwner) {
            long activeOwners =
                    userRepository.countByClientIdAndRole_NameAndActiveTrue(target.getClientId(), TenantRoles.OWNER);
            if (activeOwners <= 1) {
                throw new LastActiveOwnerException();
            }
        }
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
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
