package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.companyvendor.dto.AddPackRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.AddPriceTierRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorPackResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorPriceTierResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.UpdatePackRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.UpdateProductVendorRequest;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
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
 * The product detail page's Vendors tab (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4), now one
 * level deeper - every {@code ProductVendor} line for one product, each with its packs
 * (MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-7), each pack with its price-break children. See
 * {@link ProductVendorService} for the tenant-scoped logic this only shapes into a response; this
 * class follows the same thin-controller-plus-one-service pattern {@code ProductController}/
 * {@code StockController} already use, gated the same way {@code CompanyVendorController} gates
 * its own VIEW_VENDORS/MANAGE_VENDORS split - reads open to whoever can see the supplier
 * directory, every write reserved for whoever manages it.
 *
 * <h2>{@code {vendorId}}/{@code {packId}} are each row's OWN id</h2>
 * Same "address a resource by its own primary key" convention as before V24 - never
 * {@code companyVendorId}, a different id present only for display/linking.
 */
@RestController
@RequestMapping("/api/products/{productId}/vendors")
@RequiredArgsConstructor
public class ProductVendorController {

    private final ProductVendorService productVendorService;
    private final ProductVendorPackRepository packRepository;
    private final ProductVendorPriceTierRepository priceTierRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public List<ProductVendorResponse> list(@PathVariable UUID productId) {
        List<ProductVendor> vendors = productVendorService.list(productId);
        Map<UUID, List<ProductVendorPack>> packsByVendor = packsByVendor(productId);
        Map<UUID, List<ProductVendorPriceTier>> tiersByPack = tiersByPack(productId);
        return vendors.stream()
                .map(vendor -> ProductVendorResponse.from(
                        vendor, packsByVendor.getOrDefault(vendor.getId(), List.of()), tiersByPack))
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
        return toResponse(vendor);
    }

    @PostMapping("/{vendorId}/packs")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<ProductVendorPackResponse> addPack(
            @PathVariable UUID productId, @PathVariable UUID vendorId, @RequestBody AddPackRequest request) {
        ProductVendorPack pack = productVendorService.addPack(
                productId, vendorId, request.packagingUnit(), request.packagingSize(), request.vendorSku(), request.lastCostPrice());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductVendorPackResponse.from(pack, List.of(), productVendorService.stockUnitCodeForProduct(productId)));
    }

    @PatchMapping("/{vendorId}/packs/{packId}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ProductVendorPackResponse updatePack(
            @PathVariable UUID productId,
            @PathVariable UUID vendorId,
            @PathVariable UUID packId,
            @RequestBody UpdatePackRequest request) {
        ProductVendorPack pack = productVendorService.updatePack(
                productId, vendorId, packId, request.vendorSku(), request.lastCostPrice(), request.isDefault());
        List<ProductVendorPriceTierResponse> tiers = priceTierRepository
                .findAllByProductVendorPackIdOrderByMinQuantityAsc(packId)
                .stream()
                .map(ProductVendorPriceTierResponse::from)
                .toList();
        return ProductVendorPackResponse.from(pack, tiers, productVendorService.stockUnitCodeForProduct(productId));
    }

    @DeleteMapping("/{vendorId}/packs/{packId}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<Void> deletePack(
            @PathVariable UUID productId, @PathVariable UUID vendorId, @PathVariable UUID packId) {
        productVendorService.deletePack(productId, vendorId, packId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{vendorId}/packs/{packId}/price-tiers")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<ProductVendorPriceTierResponse> addPriceTier(
            @PathVariable UUID productId,
            @PathVariable UUID vendorId,
            @PathVariable UUID packId,
            @Valid @RequestBody AddPriceTierRequest request) {
        ProductVendorPriceTier tier =
                productVendorService.addPriceTier(productId, vendorId, packId, request.minQuantity(), request.unitPrice());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductVendorPriceTierResponse.from(tier));
    }

    @DeleteMapping("/{vendorId}/packs/{packId}/price-tiers/{tierId}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<Void> deletePriceTier(
            @PathVariable UUID productId,
            @PathVariable UUID vendorId,
            @PathVariable UUID packId,
            @PathVariable UUID tierId) {
        productVendorService.deletePriceTier(productId, vendorId, packId, tierId);
        return ResponseEntity.noContent().build();
    }

    private ProductVendorResponse toResponse(ProductVendor vendor) {
        List<ProductVendorPack> packs =
                packRepository.findAllByProductVendorIdOrderByIsDefaultDescCreatedAtAsc(vendor.getId());
        Map<UUID, List<ProductVendorPriceTier>> tiersByPack = packs.isEmpty()
                ? Map.of()
                : packs.stream()
                        .collect(Collectors.toMap(
                                ProductVendorPack::getId,
                                pack -> priceTierRepository.findAllByProductVendorPackIdOrderByMinQuantityAsc(pack.getId())));
        return ProductVendorResponse.from(vendor, packs, tiersByPack);
    }

    private Map<UUID, List<ProductVendorPack>> packsByVendor(UUID productId) {
        return packRepository.findAllByProductVendorProductIdOrderByIsDefaultDescCreatedAtAsc(productId).stream()
                .collect(Collectors.groupingBy(pack -> pack.getProductVendor().getId()));
    }

    private Map<UUID, List<ProductVendorPriceTier>> tiersByPack(UUID productId) {
        return priceTierRepository.findAllByProductVendorProductIdOrderByMinQuantityAsc(productId).stream()
                .collect(Collectors.groupingBy(tier -> tier.getProductVendorPack().getId()));
    }
}
