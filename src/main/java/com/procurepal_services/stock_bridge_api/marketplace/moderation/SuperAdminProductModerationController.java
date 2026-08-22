package com.procurepal_services.stock_bridge_api.marketplace.moderation;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.dto.ModerationProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.dto.ModerationQueueCountsResponse;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.dto.RejectProductRequest;
import com.procurepal_services.stock_bridge_api.security.SuperAdminPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing moderation for super admins.
 *
 * <p>No {@code @PreAuthorize} on top of SecurityConfig's AUD_SUPERADMIN requirement for
 * {@code /api/superadmin/**} - super admin is a single flat role, so audience is the
 * only check needed (same pattern as SuperAdminClientController).
 *
 * <p>Moderating a listing is a PLATFORM-OPERATOR action, not a marketplace-operator one,
 * which is why it lives here and not under {@code /api/marketplace/admin}. V11 made the
 * point in schema: {@code products.reviewed_by} references {@code super_admins}, not
 * {@code users}. Putting it on ProcurePal's marketplace-admin surface would mean one
 * seller adjudicating its competitors' listings, which is a conflict of interest the
 * table layout already rejects.
 */
@RestController
@RequestMapping("/api/superadmin/product-moderation")
@RequiredArgsConstructor
public class SuperAdminProductModerationController {

    private final ProductModerationService productModerationService;

    /**
     * @param status defaults to PENDING - the queue's whole purpose. Pass an explicit
     *     value for the approved/rejected tabs, or {@code all=true} for everything.
     */
    @GetMapping("/products")
    public Page<ModerationProductResponse> queue(
            @RequestParam(required = false, defaultValue = "PENDING") ProductApprovalStatus status,
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return productModerationService.queue(all ? null : status, sellerId, q, pageable);
    }

    @GetMapping("/products/{id}")
    public ModerationProductResponse get(@PathVariable UUID id) {
        return productModerationService.get(id);
    }

    @GetMapping("/counts")
    public ModerationQueueCountsResponse counts() {
        return productModerationService.counts();
    }

    @PostMapping("/products/{id}/approve")
    public ModerationProductResponse approve(
            @PathVariable UUID id, @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return productModerationService.approve(id, principal.getSuperAdminId());
    }

    /** The reason is mandatory - see {@link RejectProductRequest}. */
    @PostMapping("/products/{id}/reject")
    public ModerationProductResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectProductRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return productModerationService.reject(id, request.reason(), principal.getSuperAdminId());
    }

    /**
     * Handled here rather than globally, matching the per-feature advice convention -
     * see ClientSignupExceptionHandler for why these are scoped.
     */
    @ExceptionHandler(ModeratedProductNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ModeratedProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }
}
