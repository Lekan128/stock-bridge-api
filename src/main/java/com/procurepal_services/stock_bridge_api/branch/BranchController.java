package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A company's branches (MULTI_BRANCH_UX_PLAN.md sections 4 and 5.11). Listing needs no permission:
 * everybody needs to know where they are. Changing them needs {@code MANAGE_BRANCHES}, and is online
 * only in the app - a branch is a shared record (Pattern C.5).
 */
@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    public BranchListResponse list(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return branchService.list(principal.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public BranchResponse add(@Valid @RequestBody BranchRequest request) {
        return branchService.add(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public BranchResponse update(@PathVariable UUID id, @Valid @RequestBody BranchRequest request) {
        return branchService.update(id, request);
    }

    @PostMapping("/{id}/main")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public BranchResponse makeMain(@PathVariable UUID id) {
        return branchService.makeMain(id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public BranchResponse close(@PathVariable UUID id) {
        return branchService.close(id);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public BranchResponse reopen(@PathVariable UUID id) {
        return branchService.reopen(id);
    }

    /**
     * One low-stock level for many products at this branch (Phase 4); {@code lowStockThreshold} null
     * takes them back to each product's own. MANAGE_PRODUCTS, as a product's own per-branch levels.
     */
    @PutMapping("/{id}/thresholds")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public BulkThresholdResult setThresholds(
            @PathVariable UUID id,
            @Valid @RequestBody BulkThresholdRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return new BulkThresholdResult(branchService.setThresholds(id, request, principal.getUserId()));
    }

    public record BulkThresholdRequest(
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 5000) java.util.List<UUID> productIds,
            @jakarta.validation.constraints.Min(0) Integer lowStockThreshold) {
    }

    public record BulkThresholdResult(int updated) {
    }
}
