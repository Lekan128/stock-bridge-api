package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.companyvendor.dto.AddPriceTierRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorPriceTierResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.UpdateProductVendorRequest;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPriceTierRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product detail page's Vendors tab (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4) - every
 * {@code ProductVendor} line for one product, plus its price-break children. See {@link
 * ProductVendorService} for the tenant-scoped logic this only shapes into a response; this class
 * follows the same thin-controller-plus-one-service pattern {@code ProductController}/{@code
 * StockController} already use, gated the same way {@code CompanyVendorController} gates its own
 * VIEW_VENDORS/MANAGE_VENDORS split - reads open to whoever can see the supplier directory,
 * the preferred-swap and price-tier writes reserved for whoever manages it.
 *
 * <h2>{@code {vendorId}} is the ProductVendor row's own id, not companyVendorId</h2>
 * Confirmed against the frontend's already-built Vendors tab module, which addresses these
 * routes by {@code ProductVendor.id} - the same "address a resource by its own primary key"
 * convention {@code CompanyVendorController} uses for {@code /api/company-vendors/{id}}.
 */
@RestController
@RequestMapping("/api/products/{productId}/vendors")
@RequiredArgsConstructor
public class ProductVendorController {

    private final ProductVendorService productVendorService;
    private final ProductVendorPriceTierRepository priceTierRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public List<ProductVendorResponse> list(@PathVariable UUID productId) {
        List<ProductVendor> vendors = productVendorService.list(productId);
        Map<UUID, List<ProductVendorPriceTier>> tiersByVendor = priceTierRepository
                .findAllByProductVendorProductIdOrderByMinQuantityAsc(productId)
                .stream()
                .collect(Collectors.groupingBy(tier -> tier.getProductVendor().getId()));
        return vendors.stream()
                .map(vendor -> ProductVendorResponse.from(vendor, tiersByVendor.getOrDefault(vendor.getId(), List.of())))
                .toList();
    }

    @PatchMapping("/{vendorId}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ProductVendorResponse update(
            @PathVariable UUID productId, @PathVariable UUID vendorId, @RequestBody UpdateProductVendorRequest request) {
        ProductVendor vendor = productVendorService.update(
                productId,
                vendorId,
                request.vendorSku(),
                // lastCostPrice: never patched from this endpoint - only stockIn's own receipt
                // path refreshes it (see ProductVendorService.findOrCreateForReceipt). Not part
                // of the pinned PATCH body either.
                null,
                request.defaultPackagingUnit(),
                request.defaultPackagingSize(),
                request.isPreferred());
        return ProductVendorResponse.from(vendor, priceTierRepository.findAllByProductVendorIdOrderByMinQuantityAsc(vendorId));
    }

    @PostMapping("/{vendorId}/price-tiers")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<ProductVendorPriceTierResponse> addPriceTier(
            @PathVariable UUID productId, @PathVariable UUID vendorId, @Valid @RequestBody AddPriceTierRequest request) {
        ProductVendorPriceTier tier =
                productVendorService.addPriceTier(productId, vendorId, request.minQuantity(), request.unitPrice());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductVendorPriceTierResponse.from(tier));
    }

    @DeleteMapping("/{vendorId}/price-tiers/{tierId}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<Void> deletePriceTier(
            @PathVariable UUID productId, @PathVariable UUID vendorId, @PathVariable UUID tierId) {
        productVendorService.deletePriceTier(productId, vendorId, tierId);
        return ResponseEntity.noContent().build();
    }
}
