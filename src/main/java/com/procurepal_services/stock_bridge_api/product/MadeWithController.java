package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A made-here item's "Made in" and "Made with" (V95, selling till Step 5): read with the product, saved after it. */
@RestController
@RequestMapping("/api/products/{productId}/made-with")
@RequiredArgsConstructor
public class MadeWithController {

    private final MadeWithService madeWithService;
    private final ProductRepository productRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public MadeWithService.MadeWithResponse get(
            @PathVariable UUID productId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return madeWithService.get(theirs(productId, principal));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    @Transactional
    public MadeWithService.MadeWithResponse set(
            @PathVariable UUID productId,
            @RequestBody MadeWithService.MadeWithRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return madeWithService.set(theirs(productId, principal), request);
    }

    private Product theirs(UUID productId, AuthenticatedUserPrincipal principal) {
        return productRepository.findById(productId)
                .filter(product -> product.getClientId().equals(principal.getClientId()))
                .orElseThrow(ProductNotFoundException::new);
    }
}
