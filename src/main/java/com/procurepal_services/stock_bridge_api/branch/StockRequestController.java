package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.NoneInStoreLine;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestCreateRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestNoneInStoreRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * One area asking another for stock (STOCK_AREAS_PART_5.md). Asking needs a stock permission and work in the
 * asking area; answering ("none in the store") work in the area asked - the service checks where.
 */
@RestController
@RequiredArgsConstructor
public class StockRequestController {

    private final StockRequestService stockRequestService;

    @PostMapping("/api/stock-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('STOCK_IN', 'STOCK_OUT')")
    public StockRequestResponse ask(
            @Valid @RequestBody StockRequestCreateRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.ask(request, principal.getUserId());
    }

    @PostMapping("/api/stock-requests/{id}/none-in-store")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public StockRequestResponse noneInStore(
            @PathVariable UUID id,
            @Valid @RequestBody StockRequestNoneInStoreRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.noneInStore(id, request.productIds(), principal.getUserId());
    }

    @PostMapping("/api/stock-requests/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('STOCK_IN', 'STOCK_OUT')")
    public StockRequestResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.cancel(id, principal.getUserId());
    }

    /** The open requests this person can fill, at one branch or every one they see. */
    @GetMapping("/api/stock-requests/waiting")
    public List<StockRequestResponse> waiting(
            @RequestParam(required = false) UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.waitingFor(principal.getUserId(), branchId);
    }

    /** Lines asked for that the store has none of, for the people who buy (the Low stock page). */
    @GetMapping("/api/stock-requests/none-in-store")
    public List<NoneInStoreLine> noneInStore(
            @RequestParam(required = false) UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.noneInStoreLines(principal.getUserId(), branchId);
    }

    @GetMapping("/api/stock-requests/{id}")
    public StockRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.get(id, principal.getUserId());
    }

    @GetMapping("/api/stock-requests")
    public Page<StockRequestResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return stockRequestService.list(principal.getUserId(), branchId, Math.max(page, 0), Math.clamp(size, 1, 100));
    }
}
