package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.verification.EmailVerificationService;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.marketplace.PlatformOwnerGuard;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import com.procurepal_services.stock_bridge_api.user.InvalidRoleException;
import com.procurepal_services.stock_bridge_api.user.LastActiveOwnerException;
import com.procurepal_services.stock_bridge_api.user.PasswordMismatchException;
import com.procurepal_services.stock_bridge_api.user.RootUserDeactivationNotAllowedException;
import com.procurepal_services.stock_bridge_api.user.RootUserRoleChangeNotAllowedException;
import com.procurepal_services.stock_bridge_api.user.TenantRoles;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import com.procurepal_services.stock_bridge_api.user.UsernameTakenException;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user half of /api/superadmin: reading any tenant's users, and managing the
 * platform owner's.
 *
 * <h2>Read is broad, write is narrow, and that asymmetry is the design</h2>
 * The two halves of this class do not have the same reach, on purpose.
 * <ul>
 *   <li>The {@code ...ForClient} methods work for ANY client id, including
 *       ProcurePal's. A super admin already sees every tenant's analytics, product
 *       counts and suspension status through this same surface; who works there is
 *       no more sensitive than that, it is what "support is asking which of my
 *       colleagues has the OWNER role" needs, and it changes nothing.</li>
 *   <li>The {@code ...PlatformOwnerUser} methods reach exactly one tenant -
 *       ProcurePal - and there is no code path here that writes a user belonging
 *       to anybody else. A write to a user row is not the same kind of act as a
 *       read: creating an OWNER, or resetting a password, hands somebody a working
 *       login to a customer's inventory, orders and price list. That is a
 *       silent-account-takeover capability, and the fact that a super admin is
 *       trusted is not an argument for building the mechanism - a stolen
 *       super-admin token, or an ops user clicking the wrong row, becomes
 *       unbounded rather than bounded. Nobody asked for it either: the request was
 *       to manage ProcurePal's users and to VIEW everyone else's. A customer whose
 *       owner is locked out is served by that customer's own OWNER
 *       (UserManagementService) or, in the last resort, by a DBA who leaves an
 *       audit trail.</li>
 * </ul>
 *
 * <h2>Why the reads need no TenantScopeExecutor and the writes do</h2>
 * A super admin has no TenantContext at all (SuperAdminPrincipal is not a
 * TenantPrincipal, so TenantResolutionFilter leaves both the context and the
 * Hibernate tenant filter alone - see SuperAdminClientService for the same note).
 * With that filter disabled, {@code findAllByClientId} / {@code findByIdAndClientId}
 * are plain {@code WHERE client_id = ?} queries and isolation comes from the
 * predicate itself, which is layer 2 of the two-layer scheme and holds on its own.
 *
 * <p>Writes cannot get away with that. {@link com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity}'s
 * {@code @PrePersist} REFUSES to persist a User without a tenant context, so
 * create would fail outright; and any query issued while a context is half-set
 * would carry the wrong filter. {@link TenantScopeExecutor} moves both layers
 * together and restores both in a finally, which is why every write below is
 * wrapped in {@code callAs} rather than hand-rolling {@code TenantContext.set}.
 * The client id handed to it is always {@code client.getId()} from a Client row
 * this class loaded itself - never a path variable - which is the contract that
 * executor's Javadoc asks callers to keep.
 *
 * <h2>Which of UserManagementService's rules carry over, and which cannot</h2>
 * The tenant-facing service encodes four guards. Three of them are about the
 * tenant's own integrity and are reproduced here verbatim in intent:
 * <ul>
 *   <li>a role must be one of {@link TenantRoles#ALL} - same clean 400;</li>
 *   <li>there must always be an active OWNER - a super admin demoting ProcurePal's
 *       last owner would leave the marketplace operator unable to manage itself,
 *       which is worse here than for an ordinary tenant, not better;</li>
 *   <li>the root user cannot be demoted or deactivated - root is an ownership fact
 *       about the account holder, and ProcurePal has one like everybody else.</li>
 * </ul>
 * The fourth, {@code SelfServiceNotAllowedException}, is structurally
 * inapplicable and is deliberately absent rather than stubbed: it fires when the
 * caller is the user being edited, and a super admin is authenticated from the
 * {@code super_admins} table and can never be a row in {@code users}. There is no
 * id to compare, so no method here takes a callerId.
 *
 * <h2>Why an admin-initiated reset of the ROOT password is allowed here</h2>
 * {@code RootPasswordResetNotAllowedException} exists because an admin-set
 * password is a password the admin knows: inside a tenant, letting a co-owner
 * reset the account holder's password is a privilege ESCALATION - a peer takes
 * over the one account that outranks them. None of that reasoning survives the
 * move to this surface. A super admin does not gain anything by taking over
 * ProcurePal's root login; they can already suspend the tenant, read every
 * tenant's data, and create a fresh OWNER in this very tenant. There is no rank to
 * escalate past.
 *
 * <p>And refusing would leave a real hole. The platform owner's root credentials
 * exist in exactly two places - the {@code app.platform-owner.*} bootstrap
 * variables, which the runner ignores once the client exists, and whatever
 * password that account has now. There is no self-service reset flow in this
 * codebase. If ProcurePal's account holder leaves or loses their password,
 * refusing here means the only remaining fix is hand-written SQL against
 * production, which is strictly worse than an authenticated, purpose-built
 * endpoint. Lockout recovery is a large part of why this endpoint was asked for,
 * so it is supported on purpose and stated here so nobody "fixes" it later by
 * copying the tenant-side rule across.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminUserService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final EmailVerificationService emailVerificationService;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final TenantScopeExecutor tenantScopeExecutor;

    // ------------------------------------------------------------------------
    // Cross-tenant reads. Any client, including the platform owner.
    // ------------------------------------------------------------------------

    /**
     * The client is loaded first and the page is then keyed off
     * {@code client.getId()} rather than the raw path variable, so an id that does
     * not exist is a 404 naming the client - not an empty page, which reads
     * identically to "this tenant has no users" and would send somebody hunting for
     * a data problem that is really a typed-wrong id.
     */
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> listForClient(UUID clientId, Pageable pageable) {
        Client client = findClientOrThrow(clientId);
        return userRepository.findAllByClientId(client.getId(), pageable).map(UserSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getForClient(UUID clientId, UUID userId) {
        Client client = findClientOrThrow(clientId);
        return UserSummaryResponse.from(findUserOrThrow(client.getId(), userId));
    }

    // ------------------------------------------------------------------------
    // Platform-owner user management. Exactly one tenant, resolved server-side.
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> listPlatformOwnerUsers(Pageable pageable) {
        Client platformOwner = requirePlatformOwner();
        return userRepository.findAllByClientId(platformOwner.getId(), pageable).map(UserSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getPlatformOwnerUser(UUID userId) {
        Client platformOwner = requirePlatformOwner();
        return UserSummaryResponse.from(findUserOrThrow(platformOwner.getId(), userId));
    }

    /**
     * Creates a user in the ProcurePal tenant - and, if that tenant has none yet,
     * makes them its root user.
     *
     * <h2>"The first user created is the root user"</h2>
     * That was an explicit product requirement, and this is where it lives. When
     * {@code countByClientId == 0} the new user is created with {@code root = true}
     * and forced to OWNER regardless of the role in the request; otherwise
     * {@code root = false} and the requested role is honoured. The requested role
     * is overridden rather than rejected because there is exactly one sensible
     * answer for a tenant's first account - a STOREKEEPER who is also the account
     * holder could not create the users who would outrank them - and the response
     * echoes {@code role: "OWNER"} and {@code root: true}, so a caller who asked
     * for something else is told what they got rather than left guessing.
     *
     * <h2>This is the third root-creating path, and that is deliberate</h2>
     * ClientSignupService calls itself "the one place a root user is ever created";
     * that stopped being literally true when PlatformOwnerBootstrapRunner shipped,
     * and this is the third. All three create a root user for the same reason and
     * under the same condition - a client that has no users yet needs an account
     * holder, and only a privileged server-side flow can say who it is. What has
     * NOT changed is the rule that comment was protecting: root is never something
     * a caller can ask for. CreateUserRequest still has no {@code root} component
     * (see its Javadoc), UserManagementService still hardcodes {@code root(false)}
     * because nothing created through the tenant-facing API is ever the account
     * holder, and the flag is derived here from a count the caller cannot influence
     * rather than read from the body. A fourth path that let a request set it would
     * be the thing to refuse.
     *
     * <h2>What happens when two of these race</h2>
     * Two concurrent creates against an empty tenant would both count zero and both
     * try to insert a root user; {@code uq_users_one_root_per_client} lets one
     * through and rejects the other with a DataIntegrityViolationException that
     * SuperAdminExceptionHandler reports as a 409. Same shape as the username
     * pre-check below, which is likewise a nice message rather than the guarantee -
     * {@code uq_users_client_id_username} is the guarantee. saveAndFlush is what
     * makes both land inside this request rather than at an unrelated commit.
     */
    @Transactional
    public UserSummaryResponse createPlatformOwnerUser(CreateUserRequest request) {
        Client platformOwner = requirePlatformOwner();
        UUID clientId = platformOwner.getId();

        return tenantScopeExecutor.callAs(clientId, () -> {
            if (userRepository.findByClientIdAndUsername(clientId, request.username()).isPresent()) {
                throw new UsernameTakenException(request.username());
            }

            boolean isFirstUser = userRepository.countByClientId(clientId) == 0;
            Role role = isFirstUser ? resolveRole(TenantRoles.OWNER) : resolveRole(request.role());

            User user = userRepository.saveAndFlush(User.builder()
                    .username(request.username())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .role(role)
                    .active(true)
                    .root(isFirstUser)
                    .firstName(normalize(request.firstName()))
                    .lastName(normalize(request.lastName()))
                    .email(normalize(request.email()))
                    .phone(normalize(request.phone()))
                    .jobTitle(normalize(request.jobTitle()))
                    .build());

            // Same reasoning as UserManagementService.create - see there. Note this
            // one runs inside TenantScopeExecutor.callAs, which is harmless for the
            // token: email_verification_tokens is not tenant-scoped, so the borrowed
            // filter neither helps nor hinders the insert. It matters for the EMAIL,
            // which reads the user and the client while that scope is still open -
            // exactly as the userInvited call below it already did.
            VerificationLink verificationLink = emailVerificationService.issueLink(user);
            emailNotificationService.userInvited(user, role.getName(), verificationLink);

            return UserSummaryResponse.from(user);
        });
    }

    /**
     * Patch semantics, exactly like PUT /api/users/{id}: a body of
     * {@code {"active": false}} must not blank out the user's name and phone as a
     * side effect. See UpdateUserRequest.
     */
    @Transactional
    public UserSummaryResponse updatePlatformOwnerUser(UUID userId, UpdateUserRequest request) {
        Client platformOwner = requirePlatformOwner();
        UUID clientId = platformOwner.getId();

        return tenantScopeExecutor.callAs(clientId, () -> {
            User user = findUserOrThrow(clientId, userId);

            if (user.isRoot()) {
                if (request.role() != null) {
                    throw new RootUserRoleChangeNotAllowedException();
                }
                if (Boolean.FALSE.equals(request.active())) {
                    throw new RootUserDeactivationNotAllowedException();
                }
            }

            Role newRole = request.role() != null ? resolveRole(request.role()) : user.getRole();
            boolean newActive = request.active() != null ? request.active() : user.isActive();

            assertKeepsAtLeastOneActiveOwner(user, newRole.getName(), newActive);

            user.setRole(newRole);
            user.setActive(newActive);
            applyProfilePatch(user, request);
            return UserSummaryResponse.from(user);
        });
    }

    /**
     * Sets a password on any ProcurePal user, root included - see the class
     * Javadoc for why the root exemption the tenant surface enforces does not
     * apply to a caller who is not in the tenant at all.
     *
     * <p>Nothing here logs, returns or otherwise reproduces the plaintext: it goes
     * from the request record straight into {@code passwordEncoder.encode} and only
     * the hash is stored, and the endpoint answers 204 with no body.
     */
    @Transactional
    public void resetPlatformOwnerUserPassword(UUID userId, ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new PasswordMismatchException();
        }
        Client platformOwner = requirePlatformOwner();
        UUID clientId = platformOwner.getId();

        tenantScopeExecutor.runAs(clientId, () -> {
            User user = findUserOrThrow(clientId, userId);
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            // Doubly worth sending here: this is the one reset path that can target a
            // root user, by a caller from outside the tenant entirely. The account
            // holder learning by email that somebody reset their password is the only
            // check on that power the tenant itself has.
            emailNotificationService.passwordResetByAdmin(user);
        });
    }

    /**
     * Deactivation, not deletion - same as DELETE /api/users/{id}. A user row is
     * referenced by stock movements and orders, so removing it would either
     * cascade away history or fail on a foreign key; flipping is_active is what
     * "remove this person's access" means everywhere else in this codebase.
     */
    @Transactional
    public void deactivatePlatformOwnerUser(UUID userId) {
        Client platformOwner = requirePlatformOwner();
        UUID clientId = platformOwner.getId();

        tenantScopeExecutor.runAs(clientId, () -> {
            User user = findUserOrThrow(clientId, userId);
            if (user.isRoot()) {
                throw new RootUserDeactivationNotAllowedException();
            }
            assertKeepsAtLeastOneActiveOwner(user, user.getRole().getName(), false);
            user.setActive(false);
        });
    }

    // ------------------------------------------------------------------------
    // Shared internals
    // ------------------------------------------------------------------------

    /**
     * Read through PlatformOwnerGuard rather than ClientRepository directly, so
     * "which client is ProcurePal" is answered in one place for the whole
     * application. Its {@code requirePlatformOwner} is the wrong method here - that
     * one authorizes the CURRENT tenant from TenantContext, which a super admin
     * does not have and would fail for everyone on this surface. {@code
     * findPlatformOwner} is the plain lookup, and the empty case is a real state
     * (see PlatformOwnerNotBootstrappedException), not a broken invariant.
     */
    private Client requirePlatformOwner() {
        return platformOwnerGuard.findPlatformOwner().orElseThrow(PlatformOwnerNotBootstrappedException::new);
    }

    private Client findClientOrThrow(UUID clientId) {
        return clientRepository.findById(clientId).orElseThrow(ClientNotFoundException::new);
    }

    /**
     * findByIdAndClientId, not findById: the caller supplies both ids and only the
     * pair is meaningful. A user id belonging to a different tenant must read as
     * "not found" rather than being served under the wrong client's heading, which
     * is the same conflation UserNotFoundException's Javadoc describes.
     */
    private User findUserOrThrow(UUID clientId, UUID userId) {
        return userRepository.findByIdAndClientId(userId, clientId).orElseThrow(UserNotFoundException::new);
    }

    private Role resolveRole(String roleName) {
        if (!TenantRoles.ALL.contains(roleName)) {
            throw new InvalidRoleException(roleName);
        }
        return roleRepository.findByName(roleName)
                .orElseThrow(
                        () -> new IllegalStateException(roleName + " role not seeded - run the Flyway migrations"));
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
     * Blocks any change that would take the target user from "active owner" to not,
     * if they are currently ProcurePal's last one - the same guard, and the same
     * counting query, UserManagementService applies to every other tenant. Covers
     * role changes away from OWNER and deactivation uniformly, since both are just
     * different ways to lose the last active owner.
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
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
