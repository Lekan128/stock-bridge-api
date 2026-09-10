package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.superadmin.dto.CreateVendorRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateVendorRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketplace vendor accounts, for super admins. Audience is the only check, as
 * on every controller under {@code /api/superadmin/**} - see
 * {@code SuperAdminClientController}. Listed in
 * {@link SuperAdminExceptionHandler}'s {@code assignableTypes}, which is an
 * allow-list and must be kept so.
 *
 * <h2>Why this exists beside SuperAdminClientController rather than inside it</h2>
 * A vendor IS a {@code clients} row, so every endpoint here could in principle
 * have been a query parameter there. Three things argue against it. The response
 * shape genuinely differs - a vendor list is read for the commission rate and
 * whether the account came off the waitlist, neither of which a tenant list has.
 * Creation differs completely: there is no self-service vendor signup, so POST
 * here has no counterpart there at all. And the id space is narrowed on purpose -
 * {@code findByIdAndClientType} means a buying company's id is 404 on these paths,
 * which is what keeps a vendor screen from ever rendering a buyer's row.
 *
 * <h2>What is deliberately NOT here</h2>
 * Suspension. A vendor is a client and PUT
 * /api/superadmin/clients/{id}/status already suspends one, with the email that
 * goes with it. A second endpoint doing the same thing to the same column is two
 * places to change when the rules around suspension change, and the odds of both
 * being found are not good.
 *
 * <h2>The vendor's user account: one verb, and only one</h2>
 * An earlier revision of this comment deferred the whole question, on the grounds
 * that {@code SuperAdminUserService} argues at length against broad write access
 * to customers' user rows and that a vendor - having exactly one account - makes
 * that argument sharper rather than weaker. Both halves of that are still true.
 * What has changed is that the decision it deferred has now been made, because
 * VENDOR_RESEARCH.md section C item 9 is right that "one lost phone kills a
 * vendor's ability to trade" and nothing else in the product could fix it.
 *
 * <p>The answer is POST {@code /{id}/account/password} and nothing else. It resets
 * the password of the vendor's single account, resolved server-side; there is no
 * user id in the path, no way to name a different account, and a buying company's
 * id is a 404 on this route like every other route here. That is not the broad
 * capability the other class refuses to build - see
 * {@code SuperAdminVendorService.resetVendorAccountPassword} for the three things
 * that bound it, and for why account-owner transfer and impersonation are still
 * deliberately absent.
 */
@RestController
@RequestMapping("/api/superadmin/vendors")
@RequiredArgsConstructor
public class SuperAdminVendorController {

    private final SuperAdminVendorService superAdminVendorService;

    @GetMapping
    public Page<SuperAdminVendorSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return superAdminVendorService.listVendors(search, active, pageable);
    }

    @GetMapping("/{id}")
    public SuperAdminVendorDetail get(@PathVariable UUID id) {
        return superAdminVendorService.getVendor(id);
    }

    /**
     * Creates a vendor with no application behind it - a business ProcurePal
     * recruited offline. 201, unlike the public waitlist endpoint's 202, because
     * this genuinely created a resource the caller can address and is being handed
     * a representation of it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuperAdminVendorDetail create(@Valid @RequestBody CreateVendorRequest request) {
        return superAdminVendorService.createVendor(request);
    }

    @PutMapping("/{id}")
    public SuperAdminVendorDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateVendorRequest request) {
        return superAdminVendorService.updateVendor(id, request);
    }

    /**
     * Lockout recovery for a vendor's one account. 204 with no body, matching
     * {@code SuperAdminPlatformOwnerUserController}'s reset: the response must not
     * echo the credential, and there is nothing else worth returning.
     *
     * <p>The path is {@code /{id}/account/password} rather than
     * {@code /{id}/users/{userId}/password}. There is no user id because there is no
     * choice of user - a vendor has one - and a path shaped like the plural version
     * would invite somebody to add the collection endpoint underneath it later,
     * which is precisely the capability that is not being built.
     */
    @PostMapping("/{id}/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetAccountPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        superAdminVendorService.resetVendorAccountPassword(id, request);
    }
}
