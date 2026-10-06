package com.procurepal_services.stock_bridge_api.product.sync;

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

    /** A full copy, one page at a time. Start without {@code afterId}; keep the returned cursor. */
    @GetMapping("/snapshot")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ProductSnapshotPage snapshot(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "" + ProductSyncService.DEFAULT_LIMIT) int limit) {
        return productSyncService.snapshot(afterId, clamp(limit));
    }

    /** What changed since {@code cursor}. */
    @GetMapping("/changes")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ProductChangesPage changes(
            @RequestParam String cursor,
            @RequestParam(defaultValue = "" + ProductSyncService.DEFAULT_LIMIT) int limit) {
        return productSyncService.changes(cursor, clamp(limit));
    }

    @ExceptionHandler(InvalidSyncCursorException.class)
    public ResponseEntity<ApiError> handleInvalidCursor(InvalidSyncCursorException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, ProductSyncService.MAX_LIMIT));
    }
}
