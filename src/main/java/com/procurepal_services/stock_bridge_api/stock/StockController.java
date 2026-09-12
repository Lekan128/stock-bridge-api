package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.CostBasisAnomalyResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.ProductLotResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementSummaryResponse;
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

/**
 * Tenant-scoped stock ledger - see StockManagementService for isolation and concurrency handling.
 *
 * <p>No class-level {@code @PreAuthorize}: since STOCK_IN and STOCK_OUT (V27) split out of
 * MANAGE_INVENTORY, receiving and issuing stock are independently grantable - see the
 * separation-of-duties reasoning in the Roles & Privileges design - so each mutating endpoint
 * gates on its own privilege rather than sharing one.
 */
@RestController
@RequiredArgsConstructor
public class StockController {

    private final StockManagementService stockManagementService;
    /** Backs the one-off Phase 0 cost audit below - read-only, see that class. */
    private final CostBasisAuditService costBasisAuditService;

    @PostMapping("/api/products/{productId}/stock/stock-in")
    @PreAuthorize("hasAuthority('STOCK_IN')")
    public StockMutationResponse stockIn(
            @PathVariable UUID productId,
            @Valid @RequestBody StockInRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.stockIn(productId, request, principal.getUserId());
    }

    @PostMapping("/api/products/{productId}/stock/stock-out")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public StockMutationResponse stockOut(
            @PathVariable UUID productId,
            @Valid @RequestBody StockOutRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.stockOut(productId, request, principal.getUserId());
    }

    @PostMapping("/api/products/{productId}/stock/adjustment")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public StockMutationResponse adjust(
            @PathVariable UUID productId,
            @Valid @RequestBody StockAdjustmentRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockManagementService.adjust(productId, request, principal.getUserId());
    }

    @GetMapping("/api/products/{productId}/stock/history")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public Page<StockMovementResponse> history(
            @PathVariable UUID productId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockManagementService.history(productId, pageable);
    }

    /**
     * The open deliveries a stock-out can draw from - UNIT_UX_CONTRACT.md section 4. Replaces the
     * stock-out modal's old habit of reading the first page of {@link #history} and filtering it
     * to {@code IN}, which showed consumed lots as available, showed no remaining quantity, and
     * missed everything past page 50 (UNIT_UX_REMEDIATION_PLAN.md section 3, P1-5).
     *
     * <p>Gated by {@code MANAGE_INVENTORY} or {@code STOCK_OUT} rather than matching {@link
     * #history} exactly (as it did before V27 split STOCK_OUT out of MANAGE_INVENTORY): this is
     * what the stock-out lot picker itself calls, so a user who holds only STOCK_OUT - and not
     * the broader MANAGE_INVENTORY - must still be able to load it, or their own stock-out flow
     * breaks. It is still the same data seen from a different angle, just with a wider audience
     * now that receiving and issuing are separately grantable.
     *
     * <p>Sits under {@code /api/products/{id}/...} alongside the stock routes rather than under
     * {@code /stock/**}, because a lot is only ever asked for in the context of one product.
     *
     * @param open filter to deliveries with stock left in them. Defaults to true - the picker's
     *     case, and the only one that answers "what can I take this from".
     */
    @GetMapping("/api/products/{productId}/lots")
    @PreAuthorize("hasAnyAuthority('MANAGE_INVENTORY', 'STOCK_OUT')")
    public List<ProductLotResponse> lots(
            @PathVariable UUID productId, @RequestParam(name = "open", defaultValue = "true") boolean open) {
        return stockManagementService.lots(productId, open);
    }

    /**
     * The stock in/out report: every movement in the tenant over a date range, filterable by
     * direction, product and supplier. This is the drill-down behind the dashboard's Stock In
     * Value / Stock Out Value cards - "we took in that much, what was it" - and reads the same
     * ledger those cards aggregate over the same {@code occurredAt} range, so a total on the
     * dashboard and the rows here reconcile.
     *
     * <p>{@code from}/{@code to} bracket when the delivery or sale HAPPENED, not when it was
     * keyed in; see {@code StockMovementSpecifications.forTenant}. Sorted newest-occurring first
     * by default, which is how a person reads a ledger, with {@code createdAt} still available
     * as an explicit sort for anyone auditing data entry instead.
     *
     * <p>Gated by {@code MANAGE_INVENTORY} rather than {@code VIEW_ANALYTICS}: this is the raw
     * ledger, row by row with prices and suppliers on it, not an aggregate. It is exactly the
     * data {@link #history} already exposes one product at a time, so it takes the same
     * authority - a wider audience for the same rows would be a new disclosure wearing a report's
     * clothes.
     */
    @GetMapping("/api/stock/movements")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public Page<StockMovementResponse> allMovements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) MovementType movementType,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID companyVendorId,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockManagementService.allMovements(from, to, movementType, productId, companyVendorId, pageable);
    }

    /**
     * What the whole of {@link #allMovements}' filtered set adds up to - the report's totals row.
     * Same five filters, no paging: the sum is over everything that matches, not over the page
     * being viewed. See {@code StockMovementSummaryResponse} for why the unpriced counts come
     * back alongside the money.
     */
    @GetMapping("/api/stock/movements/summary")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public StockMovementSummaryResponse movementSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) MovementType movementType,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID companyVendorId) {
        return stockManagementService.movementSummary(from, to, movementType, productId, companyVendorId);
    }

    /**
     * The one-off cost-basis audit of UNIT_UX_REMEDIATION_PLAN.md Phase 0: which products look
     * like their stored cost was written through the P0-1 path, where a price entered per pack
     * was recorded as if it were per stock unit.
     *
     * <h2>Read-only, and there is no repair endpoint beside it</h2>
     * That absence is the design. Phase 0 is explicit that values written through the broken path
     * cannot be told apart from correct ones afterwards, so it asks for a report "and let a human
     * resolve them - do not attempt an automatic fix". A tenant corrects a figure this surfaces
     * through the ordinary product and supplier screens, where the change is attributable to a
     * person; a bulk rewrite of money on the strength of a heuristic would be neither.
     *
     * <p>Gated by this class's {@code MANAGE_INVENTORY}, i.e. exactly what the rest of the stock
     * ledger requires. Deliberately not a new authority: the report contains nothing a user with
     * inventory access cannot already read one product at a time, and inventing a permission
     * would mean a migration and a seed change for a report that is run occasionally.
     *
     * <p>Unpaginated and uncached - it is run rarely, by a person, and a stale or partial answer
     * to "which of my cost prices are wrong" is worse than a slow one.
     */
    @GetMapping("/api/stock/cost-basis-anomalies")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public List<CostBasisAnomalyResponse> costBasisAnomalies() {
        return costBasisAuditService.costBasisAnomalies();
    }

    /**
     * Traces one delivery (an {@code IN} movement/lot) forward to every sale it contributed to -
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 8/10, the recall/dispute query this feature exists
     * to answer. Sits alongside {@link #history}/{@link #allMovements} rather than under {@code
     * /api/products/{id}/stock/**} because, like {@link #allMovements}, it addresses a movement
     * directly by its own id rather than through a product.
     */
    @GetMapping("/api/stock-movements/{inMovementId}/allocations")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public List<AllocationResponse> allocations(@PathVariable UUID inMovementId) {
        return stockManagementService.allocationsForInMovement(inMovementId);
    }
}
