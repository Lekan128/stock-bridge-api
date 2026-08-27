package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.product.InvalidProductVendorException;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPriceTierRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code product_vendors} join and its price-tier children - the buyer-side "Vendors
 * tab" a product now has (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4), plus the find-or-create
 * a receipt needs (section 4/7.2/7.3). This is a service a controller can call directly; shaping
 * the REST response is a sibling module's job (the pinned {@code ProductVendorResponse} shape in
 * this module's brief), so methods here return entities rather than DTOs.
 *
 * <h2>Tenant scoping</h2>
 * Same per-service {@code requireTenantId()} convention {@code StockManagementService} and
 * {@code ProductManagementService} both use, rather than a shared utility - and every repository
 * call takes {@code clientId} explicitly, never a bare {@code findById}, for the usual
 * belt-and-braces reason.
 */
@Service
@RequiredArgsConstructor
public class ProductVendorService {

    private final ProductVendorRepository productVendorRepository;
    private final ProductVendorPriceTierRepository priceTierRepository;
    private final ProductRepository productRepository;
    private final CompanyVendorLookup companyVendorLookup;

    /** The Vendors tab: every supplier line for this product, preferred first. */
    @Transactional(readOnly = true)
    public List<ProductVendor> list(UUID productId) {
        UUID tenantId = requireTenantId();
        requireProduct(tenantId, productId);
        return productVendorRepository.findAllByClientIdAndProductId(tenantId, productId);
    }

    /**
     * Patch semantics - only non-null fields change, exactly like {@code
     * ProductManagementService.update}'s own convention. {@code isPreferred} is the one field
     * that is NOT a plain set: passing {@code true} triggers the atomic swap described below;
     * passing {@code false} or {@code null} is a no-op for that field specifically, because
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4 is explicit that there is no operation which
     * clears preferred to "nobody" - the fallback (most-recently-used) already covers that case.
     *
     * <h2>The swap</h2>
     * Setting {@code isPreferred = true} for this vendor atomically unflips whichever OTHER row
     * on the same product currently holds it, in this same transaction, so
     * {@code uq_product_vendors_one_preferred_per_product} never sees a violation - "a swap, not
     * a set" (design doc section 7.4).
     */
    @Transactional
    public ProductVendor update(
            UUID productId,
            UUID vendorId,
            String vendorSku,
            BigDecimal lastCostPrice,
            String defaultPackagingUnit,
            BigDecimal defaultPackagingSize,
            Boolean isPreferred) {
        UUID tenantId = requireTenantId();
        ProductVendor vendor = requireProductVendor(tenantId, productId, vendorId);

        if (vendorSku != null) {
            vendor.setVendorSku(vendorSku);
        }
        if (lastCostPrice != null) {
            vendor.setLastCostPrice(lastCostPrice);
        }
        if (defaultPackagingUnit != null) {
            vendor.setDefaultPackagingUnit(defaultPackagingUnit);
        }
        if (defaultPackagingSize != null) {
            vendor.setDefaultPackagingSize(defaultPackagingSize);
        }
        if (Boolean.TRUE.equals(isPreferred) && !vendor.isPreferred()) {
            productVendorRepository
                    .findByClientIdAndProductIdAndIsPreferredTrue(tenantId, productId)
                    .ifPresent(current -> {
                        current.setPreferred(false);
                        productVendorRepository.saveAndFlush(current);
                    });
            vendor.setPreferred(true);
        }

        return productVendorRepository.saveAndFlush(vendor);
    }

    /**
     * "+ Add price break". {@code minQuantity} is expected in the product's base unit - see
     * {@code ProductVendorPriceTier.minQuantity}'s javadoc; converting from a vendor's packaging
     * unit (the "+ Add price break" form's own unit-toggle, MULTI_VENDOR_INVENTORY_DESIGN.md
     * section 5.1a) is the caller's job before this is invoked.
     */
    @Transactional
    public ProductVendorPriceTier addPriceTier(UUID productId, UUID vendorId, BigDecimal minQuantity, BigDecimal unitPrice) {
        UUID tenantId = requireTenantId();
        ProductVendor vendor = requireProductVendor(tenantId, productId, vendorId);

        if (minQuantity == null || minQuantity.signum() <= 0) {
            throw new InvalidPriceTierException("minQuantity must be greater than zero.");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new InvalidPriceTierException("unitPrice must not be negative.");
        }
        if (priceTierRepository.existsByProductVendorIdAndMinQuantity(vendor.getId(), minQuantity)) {
            throw new InvalidPriceTierException("A price tier already exists at that quantity.");
        }

        ProductVendorPriceTier tier = ProductVendorPriceTier.builder()
                .productVendor(vendor)
                .minQuantity(minQuantity)
                .unitPrice(unitPrice)
                .build();
        return priceTierRepository.saveAndFlush(tier);
    }

    @Transactional
    public void deletePriceTier(UUID productId, UUID vendorId, UUID tierId) {
        UUID tenantId = requireTenantId();
        ProductVendor vendor = requireProductVendor(tenantId, productId, vendorId);
        ProductVendorPriceTier tier = priceTierRepository
                .findByIdAndProductVendorId(tierId, vendor.getId())
                .orElseThrow(PriceTierNotFoundException::new);
        priceTierRepository.delete(tier);
    }

    /**
     * Find-or-create the (product, vendor) line for a receipt and roll the delivery's cost,
     * packaging and quantity into it - the one place {@code StockManagementService.stockIn} and
     * the {@code initialVendor} path on product creation both funnel through, so the "first
     * vendor on a product is automatically preferred" rule
     * (MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1/7.3) has exactly one implementation.
     *
     * <p>{@code quantityReceivedBaseUnits} is added to BOTH {@code quantityOnHandFromVendor} and
     * {@code totalQuantityReceived} - see {@code ProductVendor}'s own javadoc for why stock-in
     * always bumps both while a later stock-out only ever decrements the first.
     *
     * @return the (possibly newly-created) vendor line, and whether it was new - the "Vendor B
     *     is new to this product" confirmation line in MULTI_VENDOR_INVENTORY_DESIGN.md section
     *     7.3 is exactly this flag.
     */
    @Transactional
    public ReceiptResult findOrCreateForReceipt(
            Product product,
            UUID companyVendorId,
            String vendorSku,
            BigDecimal costPrice,
            String packagingUnit,
            BigDecimal packagingSize,
            int quantityReceivedBaseUnits) {
        UUID tenantId = requireTenantId();
        CompanyVendor companyVendor =
                companyVendorLookup.find(companyVendorId).orElseThrow(InvalidProductVendorException::new);

        ProductVendor vendor = productVendorRepository
                .findByClientIdAndProductIdAndCompanyVendorId(tenantId, product.getId(), companyVendorId)
                .orElse(null);
        boolean vendorIsNewToProduct = vendor == null;
        if (vendorIsNewToProduct) {
            // Zero existing lines -> this one is automatically preferred. A product created via
            // CreateProductRequest.initialVendor always hits this branch, since it is by
            // definition the product's first (and, at that moment, only) vendor.
            long existingCount = productVendorRepository.countByClientIdAndProductId(tenantId, product.getId());
            vendor = ProductVendor.builder()
                    .product(product)
                    .companyVendor(companyVendor)
                    .isPreferred(existingCount == 0)
                    .quantityOnHandFromVendor(0)
                    .totalQuantityReceived(0)
                    .build();
        }

        if (vendorSku != null) {
            vendor.setVendorSku(vendorSku);
        }
        if (costPrice != null) {
            vendor.setLastCostPrice(costPrice);
        }
        if (packagingUnit != null) {
            vendor.setDefaultPackagingUnit(packagingUnit);
        }
        if (packagingSize != null) {
            vendor.setDefaultPackagingSize(packagingSize);
        }
        vendor.setQuantityOnHandFromVendor(vendor.getQuantityOnHandFromVendor() + quantityReceivedBaseUnits);
        vendor.setTotalQuantityReceived(vendor.getTotalQuantityReceived() + quantityReceivedBaseUnits);

        return new ReceiptResult(productVendorRepository.saveAndFlush(vendor), vendorIsNewToProduct);
    }

    /** Decrements the cached display rollup when a stock-out draws from this vendor's lots. */
    @Transactional
    public void recordAllocationDrawdown(ProductVendor vendor, int quantityDrawn) {
        vendor.setQuantityOnHandFromVendor(vendor.getQuantityOnHandFromVendor() - quantityDrawn);
    }

    /**
     * "Vendor A is cheaper at this quantity" - purely informational, MULTI_VENDOR_INVENTORY_DESIGN.md
     * section 5.1a/7.3: tiers only ever feed a comparison shown alongside the chosen vendor, they
     * never auto-switch it. Returns null when no OTHER vendor on this product beats the chosen
     * price at this quantity (including when the product has only one vendor).
     *
     * <p>Returns the structured facts rather than a pre-formatted sentence - the frontend's
     * receipt step ({@code StockInModal}) renders its own copy from {@code companyVendorName}/
     * {@code savingsPerUnit} rather than parsing a string, the pinned {@code
     * StockMutationResponse.CheaperVendorHint} shape this feeds into.
     */
    @Transactional(readOnly = true)
    public CheaperVendorHint cheaperVendorHint(
            UUID productId, UUID chosenCompanyVendorId, BigDecimal quantity, BigDecimal chosenUnitPrice) {
        if (quantity == null || chosenUnitPrice == null) {
            return null;
        }
        UUID tenantId = requireTenantId();
        List<ProductVendor> vendors = productVendorRepository.findAllByClientIdAndProductId(tenantId, productId);
        Map<UUID, List<ProductVendorPriceTier>> tiersByVendor = priceTierRepository
                .findAllByProductVendorProductIdOrderByMinQuantityAsc(productId)
                .stream()
                .collect(Collectors.groupingBy(tier -> tier.getProductVendor().getId()));

        ProductVendor cheapest = null;
        BigDecimal cheapestPrice = null;
        for (ProductVendor candidate : vendors) {
            if (candidate.getCompanyVendor().getId().equals(chosenCompanyVendorId)) {
                continue;
            }
            BigDecimal effective =
                    effectivePrice(candidate, tiersByVendor.getOrDefault(candidate.getId(), List.of()), quantity);
            if (effective == null || effective.compareTo(chosenUnitPrice) >= 0) {
                continue;
            }
            if (cheapestPrice == null || effective.compareTo(cheapestPrice) < 0) {
                cheapest = candidate;
                cheapestPrice = effective;
            }
        }
        if (cheapest == null) {
            return null;
        }
        return new CheaperVendorHint(
                cheapest.getCompanyVendor().getId(),
                cheapest.getCompanyVendor().getName(),
                cheapestPrice,
                chosenUnitPrice.subtract(cheapestPrice));
    }

    /** The structured facts behind "Vendor A is cheaper at this quantity" - see {@link #cheaperVendorHint}. */
    public record CheaperVendorHint(UUID companyVendorId, String companyVendorName, BigDecimal unitPrice, BigDecimal savingsPerUnit) {
    }

    /** Highest qualifying tier's price (tiers are inclusive, highest minQuantity <= quantity wins), else lastCostPrice. */
    private BigDecimal effectivePrice(ProductVendor vendor, List<ProductVendorPriceTier> tiersAscending, BigDecimal quantity) {
        BigDecimal best = null;
        for (ProductVendorPriceTier tier : tiersAscending) {
            if (quantity.compareTo(tier.getMinQuantity()) >= 0) {
                best = tier.getUnitPrice();
            }
        }
        return best != null ? best : vendor.getLastCostPrice();
    }

    private void requireProduct(UUID tenantId, UUID productId) {
        if (productRepository.findByIdAndClientId(productId, tenantId).isEmpty()) {
            throw new ProductNotFoundException();
        }
    }

    private ProductVendor requireProductVendor(UUID tenantId, UUID productId, UUID vendorId) {
        return productVendorRepository
                .findByClientIdAndIdAndProductId(tenantId, vendorId, productId)
                .orElseThrow(ProductVendorNotFoundException::new);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }

    /** The (possibly newly-created) vendor line, and whether it was new to this product. */
    public record ReceiptResult(ProductVendor vendor, boolean vendorIsNewToProduct) {
    }
}
