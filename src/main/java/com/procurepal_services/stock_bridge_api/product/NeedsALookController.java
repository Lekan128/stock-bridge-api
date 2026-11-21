package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The small line on Products and its fixes in place (BRANCH_PRICES_PLAN.md section 9.2). */
@RestController
@RequiredArgsConstructor
public class NeedsALookController {

    private final NeedsALookService needsALookService;
    private final MadeWithService madeWithService;
    private final ProductRepository productRepository;

    /** What to fix and check at {@code branchId} (absent: every branch the person sees), or for one product. */
    @GetMapping("/api/products/needs-a-look")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public NeedsALookService.Answer list(@RequestParam(required = false) UUID branchId, @RequestParam(required = false) UUID productId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return needsALookService.list(TenantContext.get(), principal.getUserId(), branchId, productId);
    }

    /** "Looks right" on something added to a branch. */
    @PostMapping("/api/products/{id}/looks-right")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> looksRight(@PathVariable UUID id, @RequestParam UUID branchId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        needsALookService.looksRight(TenantContext.get(), id, branchId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    public record AreaChoice(@NotNull UUID areaId) {}

    /** Where a made-here item is made at one branch, chosen in place from the line. */
    @PutMapping("/api/products/{id}/made-in/{branchId}")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> madeIn(@PathVariable UUID id, @PathVariable UUID branchId,
            @jakarta.validation.Valid @RequestBody AreaChoice choice) {
        Product item = productRepository.findByIdAndClientId(id, TenantContext.get()).orElseThrow(ProductNotFoundException::new);
        madeWithService.setAreaAt(item, branchId, choice.areaId());
        return ResponseEntity.noContent().build();
    }
}
