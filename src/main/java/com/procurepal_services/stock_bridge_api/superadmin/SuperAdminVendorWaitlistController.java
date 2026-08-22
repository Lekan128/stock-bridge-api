package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import com.procurepal_services.stock_bridge_api.security.SuperAdminPrincipal;
import com.procurepal_services.stock_bridge_api.superadmin.dto.ApproveVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.RejectVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorApplicationResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorWaitlistCounts;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor waitlist queue, for super admins. No {@code @PreAuthorize} on top of
 * {@code SecurityConfig}'s AUD_SUPERADMIN requirement for
 * {@code /api/superadmin/**} - super admin is a single flat role, so audience is
 * the only check needed, exactly as on {@code SuperAdminClientController}.
 *
 * <h2>This controller MUST be listed in SuperAdminExceptionHandler</h2>
 * That advice is scoped by {@code assignableTypes}, which is an allow-list: a
 * controller not named there gets none of its handlers, so a
 * {@link VendorApplicationNotFoundException} raised from here would surface as a
 * 500 instead of the 404 it was written to be. It is added there; this note exists
 * because nothing warns about the omission and the endpoint looks healthy right up
 * until something goes wrong on it.
 *
 * <h2>Why the reviewer's identity comes from the principal and never from a body</h2>
 * {@code vendor_waitlist_applications.reviewed_by} references {@code super_admins}
 * and answers "who decided this" - the one question an audit of a commercial
 * decision turns on. Reading it from the authenticated principal is what makes the
 * answer trustworthy; a request field would let any super admin record any other
 * super admin as the decider, which is worse than having no column at all because
 * it looks like evidence.
 */
@RestController
@RequestMapping("/api/superadmin/vendor-waitlist")
@RequiredArgsConstructor
public class SuperAdminVendorWaitlistController {

    private final SuperAdminVendorService superAdminVendorService;

    /**
     * @param status optional. Absent means the full history, newest first; present
     *     means that one queue, oldest first - see
     *     {@code SuperAdminVendorService.listApplications} for why the two orders
     *     differ.
     */
    @GetMapping
    public Page<VendorApplicationResponse> list(
            @RequestParam(required = false) VendorWaitlistStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return superAdminVendorService.listApplications(status, pageable);
    }

    /** Feeds the nav badge and the queue's tab labels. See VendorWaitlistCounts. */
    @GetMapping("/counts")
    public VendorWaitlistCounts counts() {
        return superAdminVendorService.counts();
    }

    @GetMapping("/{id}")
    public VendorApplicationResponse get(@PathVariable UUID id) {
        return superAdminVendorService.getApplication(id);
    }

    /**
     * Creates the vendor this application asked for and stamps the application, in
     * one transaction.
     *
     * <p>Returns the VENDOR, not the application, and that is the useful answer: an
     * ops user who has just approved somebody needs the username to tell them, which
     * is on {@code SuperAdminVendorDetail} and is the thing they would otherwise go
     * looking for. The application's new state is fully derivable - APPROVED, by
     * this caller, just now - and is one GET away for a screen that wants it.
     *
     * <p>POST rather than PUT because this creates a client, a branch and a user
     * that did not exist. It is emphatically not idempotent: a second call is a 409,
     * not a no-op. See {@link VendorApplicationAlreadyReviewedException}.
     */
    @PostMapping("/{id}/approve")
    public SuperAdminVendorDetail approve(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveVendorApplicationRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return superAdminVendorService.approve(id, request, principal.getSuperAdminId());
    }

    /** Declines, with a note that becomes the body of the applicant's email. */
    @PostMapping("/{id}/reject")
    public VendorApplicationResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectVendorApplicationRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return superAdminVendorService.reject(id, request, principal.getSuperAdminId());
    }
}
