package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped stock ledger - see StockManagementService for isolation and concurrency handling. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
public class StockController {

    private final StockManagementService stockManagementService;

    @PostMapping("/api/products/{productId}/stock/stock-in")
    public StockMutationResponse stockIn(
            @PathVariable UUID productId,
            @Valid @RequestBody StockInRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.stockIn(productId, request, principal.getUserId());
    }

    @PostMapping("/api/products/{productId}/stock/stock-out")
    public StockMutationResponse stockOut(
            @PathVariable UUID productId,
            @Valid @RequestBody StockOutRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.stockOut(productId, request, principal.getUserId());
    }

    @PostMapping("/api/products/{productId}/stock/adjustment")
    public StockMutationResponse adjust(
            @PathVariable UUID productId,
            @Valid @RequestBody StockAdjustmentRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.adjust(productId, request, principal.getUserId());
    }

    @GetMapping("/api/products/{productId}/stock/history")
    public Page<StockMovementResponse> history(
            @PathVariable UUID productId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockManagementService.history(productId, pageable);
    }

    @GetMapping("/api/stock/movements")
    public Page<StockMovementResponse> allMovements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) MovementType movementType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockManagementService.allMovements(from, to, movementType, pageable);
    }

    /**
     * Traces one delivery (an {@code IN} movement/lot) forward to every sale it contributed to -
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 8/10, the recall/dispute query this feature exists
     * to answer. Sits alongside {@link #history}/{@link #allMovements} rather than under {@code
     * /api/products/{id}/stock/**} because, like {@link #allMovements}, it addresses a movement
     * directly by its own id rather than through a product.
     */
    @GetMapping("/api/stock-movements/{inMovementId}/allocations")
    public List<AllocationResponse> allocations(@PathVariable UUID inMovementId) {
        return stockManagementService.allocationsForInMovement(inMovementId);
    }
}
