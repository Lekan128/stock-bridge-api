package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.selling.dto.AdoptLegacyPricesRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.LegacyPriceRow;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's selling switch (SELLING_PRICE_PLAN.md section 4.2). Reading it is open to anyone in
 * the company - every screen needs the answer - like {@code GET /api/company}. Changing it is the
 * owner's, on the same permission that edits the company's details.
 */
@RestController
@RequestMapping("/api/company/selling")
@RequiredArgsConstructor
public class SellingSettingsController {

    private final SellingSettingsService sellingSettingsService;

    @GetMapping
    public SellingSettingsResponse get() {
        return sellingSettingsService.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('MANAGE_COMPANY_PROFILE')")
    public SellingSettingsResponse update(
            @Valid @RequestBody UpdateSellingSettingsRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return sellingSettingsService.update(request.sellsStock(), request.source(), request.stockUse(), principal.getUserId());
    }

    /** The old-price review (SELLING_PRICE_PLAN.md section 4.6.3): products whose only price V44 set aside. */
    @GetMapping("/legacy-prices")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public Page<LegacyPriceRow> legacyPrices(@PageableDefault(size = 50) Pageable pageable) {
        return sellingSettingsService.legacyPrices(pageable);
    }

    /** Use the chosen old prices (or all of them) as selling prices; answers the settings as they now stand. */
    @PostMapping("/legacy-prices/adopt")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public SellingSettingsResponse adoptLegacyPrices(
            @RequestBody AdoptLegacyPricesRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        sellingSettingsService.adoptLegacyPrices(request, principal.getUserId());
        return sellingSettingsService.get();
    }

    /** "Not now": the offer stops; the old prices stay where V44 kept them. */
    @PostMapping("/legacy-prices/dismiss")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public SellingSettingsResponse dismissLegacyPrices() {
        return sellingSettingsService.dismissLegacyPrices();
    }
}
