package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Full user management for ProcurePal itself - the one tenant with
 * {@code clients.is_platform_owner = TRUE}. The writable counterpart to
 * SuperAdminTenantUserController's read-only cross-tenant view.
 *
 * <h2>Why the tenant is in the path as a word, not as an id</h2>
 * The path says {@code /platform-owner}, not {@code /clients/{id}}, and that is
 * the security boundary rather than a naming preference. There is no client id
 * anywhere on this controller - no path variable, no query parameter, no body
 * field - so no request can steer these writes at a different tenant. The service
 * resolves the target itself from {@code is_platform_owner}, which is the same
 * "never read the id from the caller" reasoning CompanyService gives for the
 * tenant-facing surface, applied to the one tenant a super admin is allowed to
 * write. A URL shaped {@code /clients/{id}/users} with a check that the id is the
 * platform owner's would be strictly weaker: it would mean the mechanism for
 * writing an arbitrary tenant's users exists and is one edited conditional away.
 *
 * <h2>Write narrow, read broad</h2>
 * A super admin can READ every tenant's users (that was asked for, and seeing who
 * holds which role changes nothing) but can WRITE only ProcurePal's (which is
 * what was asked for). Creating an OWNER or resetting a password in a customer's
 * tenant would be a silent-account-takeover capability over that customer's
 * inventory, orders and prices; SuperAdminUserService's class Javadoc has the full
 * argument. The same judgement applied to a lower-risk surface goes the other way:
 * PUT /api/superadmin/clients/{id} edits ANY tenant's company details, because a
 * company name and a billing contact grant access to nothing.
 *
 * <h2>Behaviour worth knowing before calling this</h2>
 * <ul>
 *   <li>If no platform owner has been bootstrapped yet, every method here answers
 *       409 with a sentence naming the configuration to set - see
 *       PlatformOwnerNotBootstrappedException. A fresh production database is
 *       genuinely in that state until somebody sets {@code app.platform-owner.*}.</li>
 *   <li>The FIRST user created in an empty ProcurePal tenant becomes the root user
 *       and is forced to OWNER whatever role the body asked for; every later one is
 *       an ordinary sub-user. The response echoes {@code root} and {@code role}, so
 *       the caller can see which happened.</li>
 *   <li>The root user cannot be demoted or deactivated, and ProcurePal cannot be
 *       left without an active OWNER - both answer 409. The root user's password
 *       CAN be reset from here, unlike on the tenant surface; that is the
 *       lockout-recovery path and SuperAdminUserService explains why the tenant-side
 *       objection does not apply to a caller outside the tenant.</li>
 * </ul>
 *
 * <p>No @PreAuthorize, matching SuperAdminClientController: SecurityConfig already
 * requires AUD_SUPERADMIN for /api/superadmin/**, and super admin is a single flat
 * role, so audience is the only check there is to make.
 */
@RestController
@RequestMapping("/api/superadmin/platform-owner/users")
@RequiredArgsConstructor
public class SuperAdminPlatformOwnerUserController {

    private final SuperAdminUserService superAdminUserService;

    /** size = 20 to match SuperAdminClientController.list - one page size across the surface. */
    @GetMapping
    public Page<UserSummaryResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return superAdminUserService.listPlatformOwnerUsers(pageable);
    }

    @GetMapping("/{userId}")
    public UserSummaryResponse get(@PathVariable UUID userId) {
        return superAdminUserService.getPlatformOwnerUser(userId);
    }

    @PostMapping
    public ResponseEntity<UserSummaryResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(superAdminUserService.createPlatformOwnerUser(request));
    }

    /**
     * The same verbs, bodies and status codes as /api/users/{id}, minus the
     * {@code @AuthenticationPrincipal} argument: those exist to compare the caller
     * against the target for the self-service guard, and a super admin authenticates
     * out of the {@code super_admins} table and can never BE one of these users. See
     * SuperAdminUserService for why that guard is absent rather than stubbed.
     */
    @PutMapping("/{userId}")
    public UserSummaryResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return superAdminUserService.updatePlatformOwnerUser(userId, request);
    }

    /** 204: the new password is never echoed back, not even as a hash. */
    @PostMapping("/{userId}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID userId, @Valid @RequestBody ResetPasswordRequest request) {
        superAdminUserService.resetPlatformOwnerUserPassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE deactivates rather than deletes, exactly like DELETE /api/users/{id} -
     * a user is referenced by stock movements and orders, so "remove their access"
     * has to mean is_active = false. The verb is DELETE because that is what the
     * tenant surface uses for the same act and a second vocabulary would be worse
     * than an imperfect one.
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID userId) {
        superAdminUserService.deactivatePlatformOwnerUser(userId);
        return ResponseEntity.noContent().build();
    }
}
