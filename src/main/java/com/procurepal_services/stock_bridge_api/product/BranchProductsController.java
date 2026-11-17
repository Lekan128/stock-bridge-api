package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The products a branch has (V92; owner, 2026-11-17): adding a new branch asks "Which products will Ikeja have?",
 * and a branch's own row and its empty Inventory offer "Add products". Adding a product to a branch adds no stock.
 * Taking one off is on the product itself (its "For" line).
 */
@RestController
@RequestMapping("/api/branches/{branchId}/products")
@RequiredArgsConstructor
public class BranchProductsController {

    private final ProductBranchService productBranchService;

    /** One product the branch doesn't have yet, for the picker. */
    public record ProductToAdd(UUID id, String name, String sku, UUID categoryId, String categoryName, boolean madeHere) {}

    /** {@code total}: how many it doesn't have at all ("Add all 48"); {@code products}: those matching the search. */
    public record ToAddResponse(long total, long matching, List<ProductToAdd> products) {}

    public record AddRequest(Boolean all, List<UUID> productIds) {}

    public record AddResponse(int added) {}

    @GetMapping("/to-add")
    @PreAuthorize("hasAnyAuthority('MANAGE_BRANCHES', 'MANAGE_PRODUCTS')")
    public ToAddResponse toAdd(
            @PathVariable UUID branchId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean madeHere,
            @RequestParam(defaultValue = "200") int limit,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return productBranchService.toAdd(principal.getClientId(), branchId, search, madeHere, Math.clamp(limit, 1, 500));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_BRANCHES', 'MANAGE_PRODUCTS')")
    public AddResponse add(
            @PathVariable UUID branchId,
            @RequestBody AddRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return new AddResponse(productBranchService.addToBranch(
                principal.getClientId(), branchId, Boolean.TRUE.equals(request.all()), request.productIds()));
    }
}
