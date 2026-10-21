package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.selling.dto.PriceChangeResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A product's selling-price history, newest first (SELLING_PRICE_PLAN.md section 4.5). */
@RestController
@RequiredArgsConstructor
public class ProductPriceHistoryController {

    private final SellingPriceService sellingPriceService;

    @GetMapping("/api/products/{productId}/price-changes")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public List<PriceChangeResponse> priceChanges(@PathVariable UUID productId) {
        return sellingPriceService.history(productId);
    }
}
