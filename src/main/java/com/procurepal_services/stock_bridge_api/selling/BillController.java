package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tables and their bills (V99, selling till Steps 7 and 9). Whoever manages branches sets the tables; whoever sells
 * opens bills, sends to them (the ordinary stock-out of each line), takes items off and takes payment.
 */
@RestController
@RequiredArgsConstructor
public class BillController {

    private final TableService tableService;
    private final BillService billService;

    // ---------------------------------------------------------------------------------- tables

    @GetMapping("/api/branches/{branchId}/tables")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public List<TableService.Table> tables(@PathVariable UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return tableService.list(principal.getClientId(), branchId);
    }

    @PostMapping("/api/branches/{branchId}/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public List<TableService.Table> addTables(
            @PathVariable UUID branchId, @RequestBody TableService.AddTablesRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return tableService.add(principal.getClientId(), branchId, request);
    }

    @PatchMapping("/api/tables/{id}")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public List<TableService.Table> updateTable(
            @PathVariable UUID id, @RequestBody TableService.TableRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return tableService.update(principal.getClientId(), id, request);
    }

    @PutMapping("/api/branches/{branchId}/tables/order")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public List<TableService.Table> orderTables(
            @PathVariable UUID branchId, @RequestBody TableService.OrderRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return tableService.reorder(principal.getClientId(), branchId, request);
    }

    // ---------------------------------------------------------------------------------- bills

    @GetMapping("/api/bills")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public List<BillService.BillSummary> openBills(
            @RequestParam UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.open(principal.getClientId(), principal.getUserId(), branchId);
    }

    @GetMapping("/api/bills/served-from")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public List<BillService.ServedFrom> servedFrom(
            @RequestParam UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.servedFrom(principal.getClientId(), principal.getUserId(), branchId);
    }

    @PostMapping("/api/bills")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public BillService.BillSummary open(
            @RequestBody BillService.OpenRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.open(principal.getClientId(), principal.getUserId(), request);
    }

    @GetMapping("/api/bills/{id}")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public BillService.Bill get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.get(principal.getClientId(), principal.getUserId(), id);
    }

    @PostMapping("/api/bills/{id}/lines/{movementId}/take-off")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public BillService.Bill takeOff(
            @PathVariable UUID id, @PathVariable UUID movementId, @RequestBody BillService.TakeOffRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.takeOff(principal.getClientId(), principal.getUserId(), id, movementId, request);
    }

    @PostMapping("/api/bills/{id}/pay")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public SaleResponse pay(
            @PathVariable UUID id, @RequestBody BillService.PayRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.pay(principal.getClientId(), principal.getUserId(), id, request);
    }

    @PostMapping("/api/bills/{id}/close")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public BillService.BillSummary close(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return billService.close(principal.getClientId(), principal.getUserId(), id);
    }
}
