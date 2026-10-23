package com.procurepal_services.stock_bridge_api.product.sync;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The on-device catalogue's sync endpoints (A3). Same authority as the Inventory list they mirror:
 * VIEW_PRODUCTS, and only the caller's own company.
 */
@RestController
@RequestMapping("/api/products/sync")
@RequiredArgsConstructor
public class ProductSyncController {

    private final ProductSyncService productSyncService;
    private final com.procurepal_services.stock_bridge_api.branch.PhoneStatusService phoneStatusService;

    /** A full copy, one page at a time. Start without {@code afterId}; keep the returned cursor. */
    @GetMapping("/snapshot")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ProductSnapshotPage snapshot(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "" + ProductSyncService.DEFAULT_LIMIT) int limit,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return productSyncService.snapshot(afterId, clamp(limit), principal.getUserId());
    }

    /**
     * What changed since {@code cursor}. The phone also says what it has waiting to send ({@code device},
     * {@code waiting}, {@code needsYou}, {@code oldestWaiting} in epoch ms, {@code branch}), for the
     * owner's branch table (V55); all optional, and an older app sends none.
     */
    @GetMapping("/changes")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ProductChangesPage changes(
            @RequestParam String cursor,
            @RequestParam(defaultValue = "" + ProductSyncService.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) UUID device,
            @RequestParam(defaultValue = "0") int waiting,
            @RequestParam(defaultValue = "0") int needsYou,
            @RequestParam(required = false) Long oldestWaiting,
            @RequestParam(required = false) UUID branch,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (device != null) {
            phoneStatusService.report(principal.getClientId(), principal.getUserId(), device, branch, waiting, needsYou, oldestWaiting);
        }
        return productSyncService.changes(cursor, clamp(limit), principal.getUserId());
    }

    @ExceptionHandler(InvalidSyncCursorException.class)
    public ResponseEntity<ApiError> handleInvalidCursor(InvalidSyncCursorException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, ProductSyncService.MAX_LIMIT));
    }
}
