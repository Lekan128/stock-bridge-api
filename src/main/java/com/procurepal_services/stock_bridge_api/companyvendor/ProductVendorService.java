package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.product.InvalidProductVendorException;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPriceTierRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code product_vendors} join, its pack children and their price-tier grandchildren -
 * the buyer-side "Vendors tab" a product now has (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4,
 * MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-7), plus the find-or-create a receipt needs
 * (section 4/7.2/7.3). This is a service a controller can call directly; shaping the REST
 * response is a sibling module's job (the pinned {@code ProductVendorResponse} shape in this
 * module's brief), so methods here return entities rather than DTOs.
 *
 * <h2>Tenant scoping</h2>
 * Same per-service {@code requireTenantId()} convention {@code StockManagementService} and
 * {@code ProductManagementService} both use, rather than a shared utility - and every repository
 * call takes {@code clientId} explicitly, never a bare {@code findById}, for the usual
 * belt-and-braces reason. Packs and tiers have no {@code client_id} of their own, so every
 * pack/tier lookup here is reached through a vendor line already resolved against the tenant.
 */
@Service
@RequiredArgsConstructor
public class ProductVendorService {

    private final ProductVendorRepository productVendorRepository;
    private final ProductVendorPackRepository productVendorPackRepository;
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
     * The product's stock unit code, for the controller to shape a single pack response
     * (add/update) without a second round trip through {@link #list}, which loads every vendor.
     */
    @Transactional(readOnly = true)
    public String stockUnitCodeForProduct(UUID productId) {
        UUID tenantId = requireTenantId();
        return productRepository
                .findByIdAndClientId(productId, tenantId)
                .orElseThrow(ProductNotFoundException::new)
                .getUnitOfMeasure();
    }

    /**
     * Patch semantics - only non-null fields change, exactly like {@code
     * ProductManagementService.update}'s own convention. {@code isPreferred} is the one field
     * that is NOT a plain set: passing {@code true} triggers the atomic swap described below;
     * passing {@code false} or {@code null} is a no-op for that field specifically, because
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4 is explicit that there is no operation which
     * clears preferred to "nobody" - the fallback (most-recently-used) already covers that case.
     *
     * <h2>vendorSku / defaultPackagingUnit / defaultPackagingSize (V24)</h2>
     * These moved onto {@link ProductVendorPack} - see that class and
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 4.2 - but this endpoint keeps accepting them as a
     * permanent alias, exactly the "old spelling accepted forever" discipline the bulk-import
     * column renames already use, applied here to a request shape instead of a header. They now
     * act on this vendor's DEFAULT pack, created on first use if none exists yet - the dedicated
     * {@code addPack}/{@code updatePack} methods below are the direct way to manage a
     * non-default pack, which this endpoint was never able to address in the first place.
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

        if (defaultPackagingUnit != null || defaultPackagingSize != null) {
            ProductVendorPack pack = findOrCreateDefaultPack(vendor);
            if (defaultPackagingUnit != null) {
                pack.setPackagingUnit(defaultPackagingUnit);
            }
            if (defaultPackagingSize != null) {
                pack.setPackagingSize(defaultPackagingSize);
            }
            validatePackagingPair(pack.getPackagingUnit(), pack.getPackagingSize());
            productVendorPackRepository.saveAndFlush(pack);
        }
        applyDefaultPackFields(vendor, vendorSku, lastCostPrice);

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
     * "+ Add pack" (MULTI_PACK_PER_VENDOR_DESIGN.md section 7.1) - a vendor's second (or third...)
     * priced offering. The first pack ever added for a vendor line becomes its default
     * automatically, the same "first one wins" rule {@link #findOrCreateForReceipt} already uses
     * one level up for {@code isPreferred}; every pack after that stays non-default until an
     * explicit {@link #updatePack} swap.
     */
    @Transactional
    public ProductVendorPack addPack(
            UUID productId,
            UUID vendorId,
            String packagingUnit,
            BigDecimal packagingSize,
            String vendorSku,
            BigDecimal lastCostPrice) {
        UUID tenantId = requireTenantId();
        requireProductVendor(tenantId, productId, vendorId);
        validatePackagingPair(packagingUnit, packagingSize);

        boolean duplicate = packagingUnit == null
                ? productVendorPackRepository.existsByProductVendorIdAndPackagingUnitIsNull(vendorId)
                : productVendorPackRepository.existsByProductVendorIdAndPackagingUnitAndPackagingSize(
                        vendorId, packagingUnit, packagingSize);
        if (duplicate) {
            throw new InvalidProductVendorPackException(packagingUnit == null
                    ? "This vendor already has a pack priced in the bare stock unit."
                    : "This vendor already has a pack with that packaging and size.");
        }

        boolean firstPackForVendor = productVendorPackRepository.countByProductVendorId(vendorId) == 0;
        ProductVendorPack pack = ProductVendorPack.builder()
                .productVendor(productVendorRepository.getReferenceById(vendorId))
                .packagingUnit(packagingUnit)
                .packagingSize(packagingSize)
                .vendorSku(vendorSku)
                .lastCostPrice(lastCostPrice)
                .isDefault(firstPackForVendor)
                .build();
        return productVendorPackRepository.saveAndFlush(pack);
    }

    /**
     * Edits a pack's cost/code, or swaps which pack is this vendor's default - the same
     * swap-not-set convention {@link #update}'s {@code isPreferred} handling already uses.
     * Packaging (container/size) is deliberately not editable here: changing what a pack
     * physically is would silently reinterpret every past receipt recorded against it -
     * {@link #deletePack} and {@link #addPack} are the correct pair of operations for "this
     * pack was wrong", matching how {@code Product.unitOfMeasure} is immutable once stock has
     * moved rather than editable in place.
     */
    @Transactional
    public ProductVendorPack updatePack(
            UUID productId, UUID vendorId, UUID packId, String vendorSku, BigDecimal lastCostPrice, Boolean isDefault) {
        UUID tenantId = requireTenantId();
        requireProductVendor(tenantId, productId, vendorId);
        ProductVendorPack pack = requirePack(vendorId, packId);

        if (vendorSku != null) {
            pack.setVendorSku(vendorSku);
        }
        if (lastCostPrice != null) {
            pack.setLastCostPrice(lastCostPrice);
        }
        if (Boolean.TRUE.equals(isDefault) && !pack.isDefault()) {
            productVendorPackRepository
                    .findByProductVendorIdAndIsDefaultTrue(vendorId)
                    .ifPresent(current -> {
                        current.setDefault(false);
                        productVendorPackRepository.saveAndFlush(current);
                    });
            pack.setDefault(true);
        }
        return productVendorPackRepository.saveAndFlush(pack);
    }

    @Transactional
    public void deletePack(UUID productId, UUID vendorId, UUID packId) {
        UUID tenantId = requireTenantId();
        requireProductVendor(tenantId, productId, vendorId);
        ProductVendorPack pack = requirePack(vendorId, packId);
        productVendorPackRepository.delete(pack);
    }

    /**
     * "+ Add price break", now against a specific pack rather than a whole vendor line -
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 4.3: "10+ bags of 50 kg" and "10+ bags of 25 kg"
     * are different breaks a vendor might set independently. <b>Both</b> numbers are in the
     * product's stock unit terms: {@code minQuantity} is a count of stock units (already the
     * case - see {@code ProductVendorPriceTier.minQuantity}'s javadoc) and {@code unitPrice} is
     * money per ONE stock unit (UNIT_UX_CONTRACT.md section 3.2).
     *
     * <p>Conversion stays the caller's job, deliberately and unchanged: this method takes no
     * {@code unit}, because a price break is configuration set on a form that knows the pack's
     * factor, not an entry made in a unit the request has to name. The form divides by the
     * pack's factor before it posts.
     */
    @Transactional
    public ProductVendorPriceTier addPriceTier(
            UUID productId, UUID vendorId, UUID packId, BigDecimal minQuantity, BigDecimal unitPrice) {
        UUID tenantId = requireTenantId();
        requireProductVendor(tenantId, productId, vendorId);
        ProductVendorPack pack = requirePack(vendorId, packId);

        if (minQuantity == null || minQuantity.signum() <= 0) {
            throw new InvalidPriceTierException("minQuantity must be greater than zero.");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new InvalidPriceTierException("unitPrice must not be negative.");
        }
        if (priceTierRepository.existsByProductVendorPackIdAndMinQuantity(pack.getId(), minQuantity)) {
            throw new InvalidPriceTierException("A price tier already exists at that quantity.");
        }

        ProductVendorPriceTier tier = ProductVendorPriceTier.builder()
                .productVendorPack(pack)
                .minQuantity(minQuantity)
                .unitPrice(unitPrice)
                .build();
        return priceTierRepository.saveAndFlush(tier);
    }

    @Transactional
    public void deletePriceTier(UUID productId, UUID vendorId, UUID packId, UUID tierId) {
        UUID tenantId = requireTenantId();
        requireProductVendor(tenantId, productId, vendorId);
        requirePack(vendorId, packId);
        ProductVendorPriceTier tier = priceTierRepository
                .findByIdAndProductVendorPackId(tierId, packId)
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
     * <h2>Which PACK the cost/code land on (V24)</h2>
     * Before a vendor could have more than one pack, "the vendor's cost" and "the vendor's
     * default pack's cost" were the same fact. Now they are not: a receipt resolved against a
     * specific pack (its {@code packagingUnit}/{@code packagingSize}, or neither for the bare
     * stock unit) must update THAT pack, not silently overwrite a different one. See
     * {@link #applyReceiptToPack} for the exact resolution, which mirrors
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 6a's "remember this pack" mechanism: a delivery in
     * a real pack nobody has on file yet is created only when {@code saveAsSupplierDefault} is
     * true, and stays a true one-off (recorded only on the resulting {@code StockMovement}
     * snapshot) when it is false. The bare-stock-unit case is not "a different pack" in that
     * sense - it is simply this vendor's ordinary price, and its cost is recorded unconditionally
     * exactly as {@code ProductVendor.lastCostPrice} always was before this table existed.
     *
     * <h2>V21: a per-delivery pack no longer rewrites the supplier's standing default</h2>
     * {@code packagingUnit}/{@code packagingSize} used to be applied unconditionally, which is
     * UNIT_UX_REMEDIATION_PLAN.md section 3's P0-5 - and the reason it is listed as a P0 rather
     * than a nuisance is that the stock-in modal told the user, in as many words, "the vendor's
     * default stays unchanged" while these four lines changed it. One delivery that happened to
     * arrive in 25 kg bags silently redefined what "a bag" meant for that supplier from then on,
     * including in the pre-filled quantities of every later form and spreadsheet.
     *
     * @param costPrice what was paid, <b>per ONE of the product's stock units</b> - per kg, never
     *     per bag. Contract section 3.2 pins that basis so it is comparable with
     *     {@code Product.costPrice}, with a pack's own price tiers, and with other suppliers'
     *     figures in {@link #cheaperVendorHint}. The caller converts (see
     *     {@code StockManagementService.resolveEntry}); passing a per-pack figure here is P0-1
     *     and was how a &#8358;45,000 bag became a &#8358;45,000 kilogram. Null when the delivery
     *     had no price, and then nothing is written.
     * @param quantityReceivedBaseUnits how much arrived, in the product's stock unit.
     * @param saveAsSupplierDefault whether a not-yet-configured real pack this delivery names
     *     should be created and kept. False for every ordinary receipt.
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
            vendor = productVendorRepository.saveAndFlush(ProductVendor.builder()
                    .product(product)
                    .companyVendor(companyVendor)
                    .isPreferred(existingCount == 0)
                    .quantityOnHandFromVendor(0)
                    .totalQuantityReceived(0)
                    .build());
        }

        applyReceiptToPack(vendor, vendorSku, costPrice, packagingUnit, packagingSize, saveAsSupplierDefault);

        vendor.setQuantityOnHandFromVendor(vendor.getQuantityOnHandFromVendor() + quantityReceivedBaseUnits);
        vendor.setTotalQuantityReceived(vendor.getTotalQuantityReceived() + quantityReceivedBaseUnits);

        return new ReceiptResult(productVendorRepository.saveAndFlush(vendor), vendorIsNewToProduct);
    }

    /**
     * See {@link #findOrCreateForReceipt}'s "Which PACK the cost/code land on" section.
     *
     * <h2>Becoming the default is a swap, exactly like {@code isPreferred} (V24 fix)</h2>
     * {@code saveAsSupplierDefault = true} on a REAL pack always claims the default, swapping off
     * whichever pack held it before - this is what makes the pre-V24 behaviour ("this delivery's
     * pack becomes the supplier's standing default") still true now that a vendor can have more
     * than one pack: two consecutive priced receipts in different pack sizes, both opted in, must
     * each replace the other as the default, not silently coexist as two non-default packs. A
     * BARE (no packaging) receipt is different - it only ever claims the default when the vendor
     * has none yet, because a receipt with no pack information must never silently displace a
     * real pack someone already configured.
     */
    private void applyReceiptToPack(
            ProductVendor vendor,
            String vendorSku,
            BigDecimal costPrice,
            String packagingUnit,
            BigDecimal packagingSize,
            boolean saveAsSupplierDefault) {
        Optional<ProductVendorPack> existing = packagingUnit == null
                ? productVendorPackRepository.findByProductVendorIdAndPackagingUnitIsNull(vendor.getId())
                : productVendorPackRepository.findByProductVendorIdAndPackagingUnitAndPackagingSize(
                        vendor.getId(), packagingUnit, packagingSize);

        ProductVendorPack pack;
        if (existing.isPresent()) {
            pack = existing.get();
        } else if (packagingUnit == null || saveAsSupplierDefault) {
            pack = productVendorPackRepository.saveAndFlush(ProductVendorPack.builder()
                    .productVendor(vendor)
                    .packagingUnit(packagingUnit)
                    .packagingSize(packagingSize)
                    .build());
        } else {
            // A one-off delivery in a real pack nobody asked to remember. Contract section 3.4's
            // "never mutates stored configuration without an explicit opt-in" applied to a pack
            // that does not exist yet, rather than one that does - nothing is created, and the
            // price this delivery paid survives only on the StockMovement snapshot itself.
            return;
        }

        if (vendorSku != null) {
            pack.setVendorSku(vendorSku);
        }
        if (costPrice != null) {
            pack.setLastCostPrice(costPrice);
        }

        Optional<ProductVendorPack> currentDefault = productVendorPackRepository.findByProductVendorIdAndIsDefaultTrue(vendor.getId());
        boolean claimsDefault = !pack.isDefault()
                && ((saveAsSupplierDefault && packagingUnit != null) || (packagingUnit == null && currentDefault.isEmpty()));
        if (claimsDefault) {
            currentDefault.ifPresent(current -> {
                current.setDefault(false);
                productVendorPackRepository.saveAndFlush(current);
            });
            pack.setDefault(true);
        }
        productVendorPackRepository.saveAndFlush(pack);
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
     * <h2>Scoped to each candidate's DEFAULT pack (V24)</h2>
     * A vendor can now have more than one priced offering; comparing "the cheapest across all of
     * them" is a real question but not this method's job yet - it stays scoped to the same single
     * figure it always compared, the vendor's default pack, so the common case (one pack, as
     * before) is unaffected and a vendor with no pack on file at all simply has nothing to offer
     * the comparison. A candidate with no default pack is skipped, not treated as free.
     *
     * <h2>Everything compared here is per stock unit</h2>
     * {@code quantity} is a count of the product's stock units; {@code chosenUnitPrice}, every
     * candidate's default pack's {@code lastCostPrice}, every tier's {@code unitPrice}, and the
     * returned {@code unitPrice}/{@code savingsPerUnit} are all money per ONE stock unit (contract
     * section 3.2). The caller must pass the RESOLVED price, not the one typed - see
     * {@code StockManagementService.stockIn}.
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
        Map<UUID, List<ProductVendorPriceTier>> tiersByPack = priceTierRepository
                .findAllByProductVendorProductIdOrderByMinQuantityAsc(productId)
                .stream()
                .collect(Collectors.groupingBy(tier -> tier.getProductVendorPack().getId()));

        ProductVendor cheapest = null;
        BigDecimal cheapestPrice = null;
        for (ProductVendor candidate : vendors) {
            if (candidate.getCompanyVendor().getId().equals(chosenCompanyVendorId)) {
                continue;
            }
            ProductVendorPack defaultPack = candidate.getDefaultPack();
            if (defaultPack == null) {
                continue;
            }
            BigDecimal effective =
                    effectivePrice(defaultPack, tiersByPack.getOrDefault(defaultPack.getId(), List.of()), quantity);
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
    private BigDecimal effectivePrice(ProductVendorPack pack, List<ProductVendorPriceTier> tiersAscending, BigDecimal quantity) {
        BigDecimal best = null;
        for (ProductVendorPriceTier tier : tiersAscending) {
            if (quantity.compareTo(tier.getMinQuantity()) >= 0) {
                best = tier.getUnitPrice();
            }
        }
        return best != null ? best : pack.getLastCostPrice();
    }

    /**
     * Sets a vendor line's default pack's code/cost, creating that pack (bare, no packaging) if
     * none exists yet. A no-op when both arguments are null, so a caller with nothing to say does
     * not create an empty pack row just to say it.
     *
     * <p>Shared by {@link #update} and catalog-import's vendor-line assertion
     * ({@code ProductCatalogRowHandler.applyVendorLine}), which builds/finds a
     * {@code ProductVendor} line directly rather than through {@link #findOrCreateForReceipt} -
     * see that method's own javadoc for why a catalog row asserting a relationship is not a
     * receipt. {@code vendor} must already be persisted (have an id) before this is called.
     */
    @Transactional
    public void applyDefaultPackFields(ProductVendor vendor, String vendorSku, BigDecimal lastCostPrice) {
        if (vendorSku == null && lastCostPrice == null) {
            return;
        }
        ProductVendorPack pack = findOrCreateDefaultPack(vendor);
        if (vendorSku != null) {
            pack.setVendorSku(vendorSku);
        }
        if (lastCostPrice != null) {
            pack.setLastCostPrice(lastCostPrice);
        }
        productVendorPackRepository.saveAndFlush(pack);
    }

    private ProductVendorPack findOrCreateDefaultPack(ProductVendor vendor) {
        return productVendorPackRepository
                .findByProductVendorIdAndIsDefaultTrue(vendor.getId())
                .orElseGet(() -> productVendorPackRepository.saveAndFlush(
                        ProductVendorPack.builder().productVendor(vendor).isDefault(true).build()));
    }

    private ProductVendorPack requirePack(UUID vendorId, UUID packId) {
        return productVendorPackRepository
                .findByIdAndProductVendorId(packId, vendorId)
                .orElseThrow(ProductVendorPackNotFoundException::new);
    }

    private void validatePackagingPair(String packagingUnit, BigDecimal packagingSize) {
        if ((packagingUnit == null) != (packagingSize == null)) {
            throw new InvalidProductVendorPackException("Give both a container and a size, or neither.");
        }
        if (packagingSize != null && packagingSize.signum() <= 0) {
            throw new InvalidProductVendorPackException("packagingSize must be greater than zero.");
        }
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
