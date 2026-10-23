package com.procurepal_services.stock_bridge_api.transfer;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.transfer.dto.ReceiptRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers between a company's branches (MULTI_BRANCH_BUILD_PLAN.md section 5). Who may do what to a
 * waybill depends on which end they work at, so the checks are in {@link TransferService}; the
 * annotations here only keep out a role that can't move stock at all.
 */
@RestController
@RequestMapping("/api/stock-transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    /** Sending. 201 whether new or a resend of the same id - the phone treats both as done. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SEND_STOCK')")
    public TransferResponse send(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, @Valid @RequestBody TransferRequest request) {
        return transferService.send(request, principal.getUserId());
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('STOCK_IN', 'STOCK_OUT', 'SEND_STOCK', 'MANAGE_INVENTORY')")
    public Page<TransferResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return transferService.list(principal.getUserId(), branchId, openOnly,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('STOCK_IN', 'STOCK_OUT', 'SEND_STOCK', 'MANAGE_INVENTORY')")
    public TransferResponse get(@AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable UUID id) {
        return transferService.get(id, principal.getUserId());
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('STOCK_IN')")
    public TransferResponse receive(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ReceiptRequest request) {
        return transferService.receive(id, request, principal.getUserId());
    }

    @PostMapping("/{id}/close")
    public TransferResponse close(@AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable UUID id) {
        return transferService.close(id, principal.getUserId());
    }

    @PostMapping("/{id}/cancel")
    public TransferResponse cancel(@AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable UUID id) {
        return transferService.cancel(id, principal.getUserId());
    }

    public record MissingCheck(@Size(max = 1000) String note) {
    }

    @PostMapping("/{id}/missing-checked")
    public TransferResponse missingChecked(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody MissingCheck request) {
        return transferService.checkMissing(id, request.note(), principal.getUserId());
    }
}
