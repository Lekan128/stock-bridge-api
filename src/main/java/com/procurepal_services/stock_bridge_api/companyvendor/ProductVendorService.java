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
     * "+ Add price break". <b>Both</b> numbers are in the product's stock unit terms:
     * {@code minQuantity} is a count of stock units (already the case - see
     * {@code ProductVendorPriceTier.minQuantity}'s javadoc) and {@code unitPrice} is money per
     * ONE stock unit, which UNIT_UX_CONTRACT.md section 3.2 now pins alongside it.
     *
     * <p>That second half is P0-2 (UNIT_UX_REMEDIATION_PLAN.md section 3). The design doc stated
     * the basis of {@code minQuantity} and was silent on {@code unitPrice}'s, and the tier form
     * duly converted the quantity to base units while sending the price straight through from a
     * field labelled "per bag" - so a stored tier meant "at 500 kg, &#8358;44,000 per bag", and
     * {@link #cheaperVendorHint} then compared that against figures that were per kg. The silence
     * was the defect; this javadoc and the contract end it.
     *
     * <p>Conversion stays the caller's job, deliberately and unchanged: this method takes no
     * {@code unit}, because a price break is configuration set on a form that knows the vendor's
     * pack, not an entry made in a unit the request has to name. The form divides by the pack's
     * factor before it posts - the same factor {@code UnitOptions} publishes to it.
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
     * Find-or-create the (product, vendor) line for a receipt and roll the delivery's cost and
     * quantity into it - the one place {@code StockManagementService.stockIn} and the
     * {@code initialVendor} path on product creation both funnel through, so the "first vendor
     * on a product is automatically preferred" rule (MULTI_VENDOR_INVENTORY_DESIGN.md section
     * 5.1/7.3) has exactly one implementation.
     *
     * <p>{@code quantityReceivedBaseUnits} is added to BOTH {@code quantityOnHandFromVendor} and
     * {@code totalQuantityReceived} - see {@code ProductVendor}'s own javadoc for why stock-in
     * always bumps both while a later stock-out only ever decrements the first.
     *
     * <h2>V21: a per-delivery pack no longer rewrites the supplier's standing default</h2>
     * {@code packagingUnit}/{@code packagingSize} used to be applied unconditionally, which is
     * UNIT_UX_REMEDIATION_PLAN.md section 3's P0-5 - and the reason it is listed as a P0 rather
     * than a nuisance is that the stock-in modal told the user, in as many words, "the vendor's
     * default stays unchanged" while these four lines changed it. One delivery that happened to
     * arrive in 25 kg bags silently redefined what "a bag" meant for that supplier from then on,
     * including in the pre-filled quantities of every later form and spreadsheet.
     *
     * <p>They are now applied only when {@code saveAsSupplierDefault} is true - contract section
     * 3.4 and non-negotiable 7, "a per-delivery override never mutates stored configuration
     * without an explicit opt-in on the same screen". The per-delivery fact is not lost by this:
     * it is snapshotted onto the {@code StockMovement} row itself, which is where a fact about
     * one delivery belongs and where it already went.
     *
     * <p>{@code costPrice} is deliberately NOT behind the flag. A price paid is a running fact
     * about the relationship, not a configuration choice somebody makes - contract section 3.4
     * draws the line there explicitly.
     *
     * @param costPrice what was paid, <b>per ONE of the product's stock units</b> - per kg, never
     *     per bag. It lands in {@code ProductVendor.lastCostPrice}, which contract section 3.2
     *     pins to that basis so it is comparable with {@code Product.costPrice}, with this line's
     *     price tiers, and with other suppliers' figures in {@link #cheaperVendorHint}. The
     *     caller converts (see {@code StockManagementService.resolveEntry}); passing a
     *     per-pack figure here is P0-1 and was how a &#8358;45,000 bag became a &#8358;45,000
     *     kilogram. Null when the delivery had no price, and then nothing is written.
     * @param quantityReceivedBaseUnits how much arrived, in the product's stock unit.
     * @param saveAsSupplierDefault whether this delivery's pack should also become this
     *     supplier's standing default. False for every ordinary receipt.
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
            int quantityReceivedBaseUnits,
            boolean saveAsSupplierDefault) {
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
        // Contract section 3.4: configuration changes only on an explicit opt-in. A brand-new
        // vendor line is not an exception - it has no default to protect, but silently seeding
        // one from a single delivery is the same act, and the same screen offers the checkbox.
        if (saveAsSupplierDefault) {
            if (packagingUnit != null) {
                vendor.setDefaultPackagingUnit(packagingUnit);
            }
            if (packagingSize != null) {
                vendor.setDefaultPackagingSize(packagingSize);
            }
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
     * <h2>Everything compared here is per stock unit</h2>
     * {@code quantity} is a count of the product's stock units; {@code chosenUnitPrice}, every
     * candidate's {@code lastCostPrice}, every tier's {@code unitPrice}, and the returned
     * {@code unitPrice}/{@code savingsPerUnit} are all money per ONE stock unit (contract section
     * 3.2). The caller must pass the RESOLVED price, not the one typed - see
     * {@code StockManagementService.stockIn}. Before that was true this method was comparing a
     * per-bag receipt price against per-kg tier prices, so the hint fired, or failed to, for
     * reasons unrelated to which supplier was actually cheaper (P0-2). A hint that is sometimes
     * right by accident is worse than none, because a user cannot tell the two cases apart.
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
