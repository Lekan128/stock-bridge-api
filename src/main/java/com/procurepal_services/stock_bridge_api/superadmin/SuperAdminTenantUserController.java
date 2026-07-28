package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of any tenant's users - "super admin should be able to view
 * other tenant users", and nothing more than view.
 *
 * <h2>Read-only is the whole point of the separate controller</h2>
 * There is no POST, PUT or DELETE here and that is not an omission to be filled
 * in later. Writing a user row for an arbitrary customer means handing out (or
 * resetting) a working login to that customer's inventory, orders and pricing;
 * see SuperAdminUserService's class Javadoc for why that capability is not built
 * rather than merely not exposed. Keeping the writable surface on its own path
 * (SuperAdminPlatformOwnerUserController) means the distinction is visible in the
 * URL and in the file listing, not buried in a conditional.
 *
 * <p>Sitting under /api/superadmin/clients/{clientId}/ rather than on a flat
 * /api/superadmin/users?clientId= is deliberate too: a tenant's users are a
 * sub-collection of that tenant, the client id is mandatory (there is no
 * "everybody's users" query and no tenant context to fall back on), and an id in
 * the path cannot be forgotten the way a query parameter can.
 *
 * <p>No @PreAuthorize, matching SuperAdminClientController: SecurityConfig
 * already requires AUD_SUPERADMIN for /api/superadmin/**, and super admin is a
 * single flat role, so audience is the only check there is to make.
 */
@RestController
@RequestMapping("/api/superadmin/clients/{clientId}/users")
@RequiredArgsConstructor
public class SuperAdminTenantUserController {

    private final SuperAdminUserService superAdminUserService;

    /** size = 20 to match SuperAdminClientController.list - one page size across the surface. */
    @GetMapping
    public Page<UserSummaryResponse> list(
            @PathVariable UUID clientId, @PageableDefault(size = 20) Pageable pageable) {
        return superAdminUserService.listForClient(clientId, pageable);
    }

    /**
     * UserSummaryResponse is the same record /api/users serves, and it carries no
     * passwordHash - it is built field by field in {@code UserSummaryResponse.from}
     * from an explicit list that does not include it, rather than by serialising
     * the entity, so a column added to User later cannot leak through here either.
     * That is the single most important property of this controller.
     */
    @GetMapping("/{userId}")
    public UserSummaryResponse get(@PathVariable UUID clientId, @PathVariable UUID userId) {
        return superAdminUserService.getForClient(clientId, userId);
    }
}
