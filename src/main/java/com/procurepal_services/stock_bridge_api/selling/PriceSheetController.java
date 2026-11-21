package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price sheet (BRANCH_PRICES_PLAN.md §10, step 2): many prices typed like a spreadsheet, saved a few cells at a
 * time. A cell is a product's price at a branch (with prices per branch on - every branch sharing that branch's prices
 * gets it) or its one price (switch off). The sheet reads from the phone's copy of the catalogue, which already
 * carries every price; only the saving is here. Each cell is a change only when it differs, needs Change selling
 * prices, and with branch prices on someone who sees every branch it is used at (D3).
 */
@RestController
@RequiredArgsConstructor
public class PriceSheetController {

    private final ProductRepository productRepository;
    private final SellingPriceService sellingPriceService;
    private final SellingPricePermission sellingPricePermission;
    private final BranchPriceService branchPriceService;

    /** One cell: {@code branchId} null for the one price; {@code price} null takes it off; typed per {@code unit}. */
    public record Cell(@NotNull UUID productId, UUID branchId, @DecimalMin(value = "0", inclusive = true) BigDecimal price, String unit) {}

    public record SaveRequest(@NotNull @Size(min = 1, max = 500) List<@Valid Cell> cells) {}

    /** What each saved cell now holds, per stock unit and as typed, for the sheet to show. */
    public record Saved(UUID productId, UUID branchId, BigDecimal unitPrice, BigDecimal unitPriceEntered, String unitPriceEnteredUnit) {}

    public record SaveResponse(List<Saved> saved) {}

    @PutMapping("/api/prices")
    @PreAuthorize("hasAuthority('CHANGE_SELLING_PRICES')")
    @Transactional
    public SaveResponse save(@Valid @RequestBody SaveRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        UUID tenantId = TenantContext.get();
        UUID userId = principal.getUserId();
        boolean on = branchPriceService.isOn(tenantId);
        List<Saved> saved = new ArrayList<>();
        for (Cell cell : request.cells()) {
            Product product = productRepository.findByIdAndClientId(cell.productId(), tenantId).orElseThrow(ProductNotFoundException::new);
            if (on && cell.branchId() != null) {
                if (!branchPriceService.isCurrentAt(product, cell.branchId(), cell.price(), cell.unit())) {
                    branchPriceService.setAtBranch(product, cell.branchId(), cell.price(), cell.unit(), userId);
                }
                SellingPriceService.Snapshot at = branchPriceService.priceAt(product, cell.branchId());
                saved.add(new Saved(product.getId(), cell.branchId(), at == null ? null : at.unitPrice(),
                        at == null ? null : at.entered(), at == null ? null : at.enteredUnit()));
                continue;
            }
            if (on) {
                throw new BranchPriceException("Each branch has its own prices: say which branch this price is for.");
            }
            sellingPricePermission.require(userId);
            SellingPriceService.Snapshot before = SellingPriceService.Snapshot.of(product);
            sellingPriceService.apply(product, cell.price(), cell.unit());
            sellingPriceService.recordIfChanged(product, before, ProductPriceChange.Source.FORM, userId);
            productRepository.save(product);
            saved.add(new Saved(product.getId(), null, product.getUnitPrice(), product.getUnitPriceEntered(), product.getUnitPriceEnteredUnit()));
        }
        return new SaveResponse(saved);
    }
}
