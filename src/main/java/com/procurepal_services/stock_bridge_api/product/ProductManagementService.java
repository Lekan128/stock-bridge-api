package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationService;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductExcelService;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductTemplateContext;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductVendorSnapshot;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.product.sku.ProductSkuSettingsService;
import com.procurepal_services.stock_bridge_api.product.sku.SkuGenerationService;
import com.procurepal_services.stock_bridge_api.product.sku.dto.ProductSkuSettingsResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.SkuPreviewResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.UpdateProductSkuSettingsRequest;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * All reads/writes go through ProductRepository's tenant-scoped methods (or
 * ProductSpecifications, which adds an explicit client_id predicate) - see
 * UserManagementService for the same principle applied to users.
 *
 * <h2>This is also a SELLER's catalogue editor</h2>
 * The same endpoints serve a buying company managing its private stock list and a
 * vendor managing the products it sells, because to the tenant they are the same
 * screen. Moderation therefore has to be applied here rather than on a separate
 * vendor-only surface, and it has to be applied CONDITIONALLY - a buying company must
 * never be dragged into a review queue for editing its own napkin count. The condition
 * lives in {@link ProductModerationRules}; this class only calls it.
 */
@Service
@RequiredArgsConstructor
public class ProductManagementService {

    /**
     * The note on every {@code StockMovement} an import's opening balance writes, spelled exactly
     * as BULK_IMPORT_DESIGN.md section 3 specifies. Fixed rather than composed because it is
     * user-facing text on the movement history, and because it is the string anything looking for
     * "stock that came in from a spreadsheet rather than a delivery" will match on.
     */

    private final com.procurepal_services.stock_bridge_api.product.category.CompanyCategoryService companyCategoryService;
    private final com.procurepal_services.stock_bridge_api.repository.CompanyCategoryRepository companyCategoryRepository;
    private final ProductRepository productRepository;
    private final S3ImageService s3ImageService;
    private final ProductExcelService productExcelService;
    private final SellerDirectory sellerDirectory;
    private final ProductModerationService productModerationService;
    /**
     * V19: the buyer-side product<->vendor join - see {@code ProductVendor}. Used here only to
     * populate {@code ProductResponse.preferredVendorName}; every other vendor-management
     * operation (adding a line, editing cost/packaging, toggling preferred) lives in
     * {@code companyvendor.ProductVendorService} instead.
     */
    private final ProductVendorRepository productVendorRepository;
    private final ProductVendorPackRepository productVendorPackRepository;
    /** Backs the V19 {@code unitOfMeasure} immutability guard in {@link #update}. */
    private final StockMovementRepository stockMovementRepository;
    /**
     * Reused, not reimplemented, for {@code CreateProductRequest.initialVendor}: {@code stockIn}
     * already owns the ledger write, the weighted-average cost recalculation and the
     * find-or-create {@code ProductVendor} line, and a second copy of that logic here would be
     * the one that drifts. See {@link #create}.
     */
    private final StockManagementService stockManagementService;
    /**
     * The tenant's own supplier directory. Two uses, both added with the per-tenant template: the
     * {@code vendor_name} dropdown a template is generated with, and resolving what an imported
     * row's {@code vendor_name} cell refers to. Read-only from here - creating a directory entry
     * is {@code CompanyVendorService}'s job, and an import must never create one silently (see
     * {@link #openingBalanceVendorId}).
     */
    private final CompanyVendorRepository companyVendorRepository;
    private final ProductSkuSettingsService productSkuSettingsService;
    private final SkuGenerationService skuGenerationService;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, Boolean active, Pageable pageable) {
        return list(search, active, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, Boolean active, UUID categoryId, Pageable pageable) {
        UUID tenantId = requireTenantId();
        Page<Product> page = productRepository.findAll(
                ProductSpecifications.forTenant(tenantId, search, active, categoryId), pageable);
        Map<UUID, String> preferredVendorNames = preferredVendorNamesFor(tenantId, page.getContent());
        Map<UUID, Boolean> hasMultiplePacks = hasMultiplePacksFor(page.getContent());
        return page.map(product -> ProductResponse.from(
                product,
                preferredVendorNames.get(product.getId()),
                null,
                hasMultiplePacks.getOrDefault(product.getId(), false)));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        UUID tenantId = requireTenantId();
        Product product = findTenantProductOrThrow(id);
        String preferredVendorName = productVendorRepository
                .findByClientIdAndProductIdAndIsPreferredTrue(tenantId, id)
                .map(vendor -> vendor.getCompanyVendor().getName())
                .orElse(null);
        boolean hasMultiplePacks = hasMultiplePacksFor(List.of(product)).getOrDefault(id, false);
        return ProductResponse.from(product, preferredVendorName, null, hasMultiplePacks);
    }

    /**
     * Batched, not per-row: {@code Product.vendors} is a LAZY association specifically so a
     * page of products never pays for loading it unless something explicitly asks - see that
     * field's javadoc. One query for the whole page, with {@code companyVendor} eagerly joined,
     * rather than one query per row.
     */
    private Map<UUID, String> preferredVendorNamesFor(UUID tenantId, List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        return productVendorRepository.findPreferredByClientIdAndProductIdIn(tenantId, productIds).stream()
                .collect(Collectors.toMap(
                        vendor -> vendor.getProduct().getId(), vendor -> vendor.getCompanyVendor().getName()));
    }

    /**
     * Batched, same N+1-avoidance shape as {@link #preferredVendorNamesFor}: one query against
     * {@code product_vendor_packs} for the whole page rather than one per row.
     *
     * <p>A product "has multiple packs" when more than one distinct (container, size) shape is
     * in play for it - its own catalog pack (a fact independent of any vendor,
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 3) plus every one of its vendors' packs. A product
     * whose only pack is its own, with zero vendor-specific overrides, reports {@code false} -
     * that field is not "a default among several", it is the only one.
     */
    private Map<UUID, Boolean> hasMultiplePacksFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> shapesByProduct = new HashMap<>();
        for (Product product : products) {
            if (product.getPackagingUnit() == null) continue;
            shapesByProduct
                    .computeIfAbsent(product.getId(), id -> new HashSet<>())
                    .add(packShapeKey(product.getPackagingUnit(), product.getPackagingSize()));
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        for (ProductVendorPackRepository.PackShape shape :
                productVendorPackRepository.findPackShapesByProductIdIn(productIds)) {
            shapesByProduct
                    .computeIfAbsent(shape.getProductId(), id -> new HashSet<>())
                    .add(packShapeKey(shape.getPackagingUnit(), shape.getPackagingSize()));
        }
        Map<UUID, Boolean> result = new HashMap<>();
        shapesByProduct.forEach((id, shapes) -> result.put(id, shapes.size() > 1));
        return result;
    }

    private static String packShapeKey(String packagingUnit, BigDecimal packagingSize) {
        String unit = packagingUnit == null ? "NONE" : packagingUnit;
        String size = packagingSize == null ? "" : packagingSize.stripTrailingZeros().toPlainString();
        return unit + "|" + size;
    }

    @Transactional(readOnly = true)
    public ProductSkuSettingsResponse getSkuSettings() {
        return productSkuSettingsService.get(requireTenantId());
    }

    @Transactional
    public ProductSkuSettingsResponse updateSkuSettings(UpdateProductSkuSettingsRequest request) {
        return productSkuSettingsService.update(requireTenantId(), request);
    }

    @Transactional(readOnly = true)
    public SkuPreviewResponse previewSku() {
        SkuGenerationService.Preview preview = skuGenerationService.preview(requireTenantId());
        return new SkuPreviewResponse(preview.sku(), preview.nextSequence());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> lowStock() {
        return productRepository.findLowStockByClientId(requireTenantId()).stream()
                .map(ProductResponse::from)
                .toList();
    }

    /** Delegates to {@link #create(CreateProductRequest, MultipartFile, UUID)} with no acting user. */
    @Transactional
    public ProductResponse create(CreateProductRequest request, MultipartFile image) {
        return create(request, image, null);
    }

    /**
     * @param actingUserId attributed on the opening {@code StockMovement} when {@code
     *     request.initialVendor()} is present - see {@code StockMovement.createdBy}. The
     *     pre-V19 {@code ProductController} calls the two-argument overload above, which passes
     *     null here (a movement with no {@code createdBy}, same as any other system-attributed
     *     write); a controller that has the authenticated principal available should call this
     *     overload directly instead.
     */
    @Transactional
    public ProductResponse create(CreateProductRequest request, MultipartFile image, UUID actingUserId) {
        UUID tenantId = requireTenantId();

        // See CreateProductRequest's "sku is conditionally required" javadoc: whichever branch
        // runs, request.sku() plays no further part below - `sku` is the one value this method
        // actually uses.
        String sku;
        if (productSkuSettingsService.isEnabled(tenantId)) {
            sku = skuGenerationService.generateAndReserveOne(tenantId, request.name());
        } else {
            if (request.sku() == null || request.sku().isBlank()) {
                throw new SkuRequiredException();
            }
            assertSkuAvailable(tenantId, request.sku(), null);
            sku = request.sku();
        }

        // One lookup answers two questions: initialStatusFor below (existing) and isSeller
        // here (new) - see the class javadoc on "this is also a SELLER's catalogue editor".
        Client owner = sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
        boolean isSeller = owner != null && owner.canSell();

        // A seller's product IS a listing - the selling price is the entire reason a buyer
        // would look at it - so it may never be created without one. An ordinary buying
        // company has no selling price at all; request.unitPrice() is simply discarded for
        // one below rather than rejected, since there is nothing meaningful for the field to
        // mean on a company's own private stock even if a stale client still sends it.
        if (isSeller && request.unitPrice() == null) {
            throw new UnitPriceRequiredException();
        }

        String unitOfMeasure = resolveUnitOfMeasure(request.unitOfMeasure());
        String packagingUnit = resolvePackagingUnit(request.packagingUnit());
        requirePackagingUnitAndSizePaired(packagingUnit, request.packagingSize());
        requirePackagingImpliesUnitOfMeasure(unitOfMeasure, packagingUnit, request.packagingSize());

        // V19: no .companyVendor(...) builder call any more - Product has no such field. A
        // supplier is now linked via ProductVendor, either below (initialVendor) or, for a
        // product created with none, later through ProductVendorService/StockManagementService.
        Product product = Product.builder()
                .name(request.name())
                .sku(sku)
                .description(request.description())
                .unitPrice(isSeller ? request.unitPrice() : null)
                .lowStockThreshold(request.lowStockThreshold())
                .unitOfMeasure(unitOfMeasure)
                .packagingUnit(packagingUnit)
                .packagingSize(request.packagingSize())
                .quantityOnHand(0)
                .incomingQuantity(0)
                .active(true)
                // Stated rather than left to the column's PENDING default, so the
                // platform owner's auto-approval is a rule somebody can find. For a
                // vendor this is PENDING and the product enters the moderation queue;
                // for an ordinary buying company it is also PENDING and is never read
                // by anything - see ProductModerationRules.
                .approvalStatus(ProductModerationRules.initialStatusFor(owner))
                .companyCategory(request.categoryId() == null ? null : companyCategoryService.require(request.categoryId()))
                .build();

        List<String> warnings = new ArrayList<>();
        if (hasContent(image)) {
            applyImage(product, image, warnings);
        }

        // Flushed, not merely saved: request.initialVendor() below (when present) writes a
        // StockMovement through StockManagementService.stockIn in the SAME transaction, and
        // that service locks the product row with a SELECT ... FOR UPDATE against the database
        // - it must already be able to find this row. Same reasoning
        // IncomingStockService.matchOrCreateBuyerProduct's own saveAndFlush documents.
        product = productRepository.saveAndFlush(product);

        String preferredVendorName = null;
        if (request.initialVendor() != null) {
            CreateProductRequest.InitialVendor initialVendor = request.initialVendor();
            // Reused, not reimplemented: stockIn already owns the ledger write, the weighted-
            // average cost recalculation (trivial here since quantityOnHand starts at zero -
            // the new cost IS initialVendor.cost()) and the find-or-create ProductVendor line,
            // which this - the product's very first vendor - makes preferred automatically.
            // "unit" is left null: initialVendor.quantity() is already in the product's own
            // unitOfMeasure, there being no other configured unit yet to offer a toggle for.
            // initialVendor.cost() is therefore already per stock unit, factor 1, and stockIn's
            // V21 price conversion is an identity on this path - contract non-negotiable 8.
            //
            // saveAsSupplierDefault = TRUE, the one place in the codebase that passes it. The
            // guard it opts out of (contract section 3.4) exists to stop a DELIVERY silently
            // redefining a supplier's standing pack; this is not a delivery, it is the form on
            // which the user is configuring the product and its first supplier together, and
            // initialVendor.packagingUnit()/packagingSize() are fields they filled in for
            // exactly that purpose. Defaulting to false here would mean a pack typed on the
            // create-product screen vanished on save - a different broken promise, in the
            // opposite direction.
            stockManagementService.stockIn(
                    product.getId(),
                    new StockInRequest(
                            initialVendor.quantity(),
                            initialVendor.cost(),
                            "Opening stock",
                            null,
                            initialVendor.companyVendorId(),
                            initialVendor.packagingUnit(),
                            initialVendor.packagingSize(),
                            null,
                            true),
                    actingUserId);
            preferredVendorName = productVendorRepository
                    .findByClientIdAndProductIdAndIsPreferredTrue(tenantId, product.getId())
                    .map(vendor -> vendor.getCompanyVendor().getName())
                    .orElse(null);
        }

        return ProductResponse.from(product, preferredVendorName, warnings.isEmpty() ? null : warnings);
    }

    /** Delegates to {@link #update(UUID, UpdateProductRequest, MultipartFile, AuthenticatedUserPrincipal)} with no principal - the sku-override permission check treats that as "not permitted." */
    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request, MultipartFile image) {
        return update(id, request, image, null);
    }

    @Transactional
    public ProductResponse update(
            UUID id, UpdateProductRequest request, MultipartFile image, AuthenticatedUserPrincipal principal) {
        Product product = findTenantProductOrThrow(id);

        // Same lookup create() makes, against the product's own tenant rather than the
        // caller's TenantContext - the two are the same id here (findTenantProductOrThrow
        // already scoped the row to the caller), but naming it this way says what the
        // question actually is: does the OWNER of this row sell.
        Client owner = sellerDirectory.findSellerOfRecord(product.getClientId()).orElse(null);
        boolean isSeller = owner != null && owner.canSell();

        // Snapshot the identity fields BEFORE any mutation, so the moderation check at
        // the bottom compares what the listing was against what it became. Captured
        // even for tenants that are not sellers - it is seven string/decimal reads, and
        // making it conditional would mean two code paths through this method.
        String beforeName = product.getName();
        String beforeSku = product.getSku();
        String beforeDescription = product.getDescription();
        String beforeBrand = product.getBrand();
        String beforeImageUrl = product.getImageUrl();
        String beforeUnitOfMeasure = product.getUnitOfMeasure();
        String beforePackagingUnit = product.getPackagingUnit();
        BigDecimal beforePackagingSize = product.getPackagingSize();

        if (request.sku() != null && !request.sku().equals(product.getSku())) {
            // Auto-generation locks the field client-side, but the server is the actual
            // enforcement - see SkuOverrideNotPermittedException. Disabled tenants are
            // unaffected: any MANAGE_PRODUCTS holder may still edit sku freely, exactly as
            // before this feature existed.
            if (productSkuSettingsService.isEnabled(product.getClientId()) && !hasSkuOverrideAuthority(principal)) {
                throw new SkuOverrideNotPermittedException();
            }
            assertSkuAvailable(product.getClientId(), request.sku(), id);
            product.setSku(request.sku());
        }
        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.unitPrice() != null) {
            // A non-seller has no selling-price surface at all - see create() - so a
            // stale client still sending one here is silently ignored rather than
            // applied or rejected.
            if (isSeller) {
                product.setUnitPrice(request.unitPrice());
            }
        }
        // A seller's product may never be LEFT without a price by this patch: either the
        // request just cleared/omitted it and none was there before, or it never had one
        // to begin with. Checked against the RESULTING value, immediately after the only
        // block that can change it, rather than deferred to the bottom of the method.
        if (isSeller && product.getUnitPrice() == null) {
            throw new UnitPriceRequiredException();
        }
        if (request.lowStockThreshold() != null) {
            product.setLowStockThreshold(request.lowStockThreshold());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        if (request.unitOfMeasure() != null) {
            // V19: unitOfMeasure is the unit every historical StockMovement quantity is
            // implicitly recorded in, so it may never actually CHANGE once any movement
            // exists - see Product.unitOfMeasure's javadoc. Resolved against the fixed unit
            // list first (same InvalidUnitOfMeasureException as always for a bad code, since
            // that failure is unconditional), then compared to the RESOLVED existing value -
            // resending the same unit the product already has is a no-op, not a violation, so
            // only an actual change trips the guard.
            String resolvedUnitOfMeasure = resolveUnitOfMeasure(request.unitOfMeasure());
            // A product saved before units existed (null) may be given its first one even with
            // stock recorded - that is the one-time fix of task 1.8, and it names what the
            // numbers already in the ledger were counted in. Changing a unit it HAS stays refused.
            if (product.getUnitOfMeasure() != null
                    && !Objects.equals(resolvedUnitOfMeasure, product.getUnitOfMeasure())
                    && stockMovementRepository.existsByProductIdAndClientId(id, product.getClientId())) {
                throw new UnitOfMeasureImmutableException();
            }
            product.setUnitOfMeasure(resolvedUnitOfMeasure);
        }
        if (request.packagingUnit() != null) {
            product.setPackagingUnit(resolvePackagingUnit(request.packagingUnit()));
        }
        if (request.packagingSize() != null) {
            product.setPackagingSize(request.packagingSize());
        }
        if (Boolean.TRUE.equals(request.clearCategory())) {
            product.setCompanyCategory(null);
        } else if (request.categoryId() != null) {
            product.setCompanyCategory(companyCategoryService.require(request.categoryId()));
        }
        // Both checked against the RESULTING state, not the request: a patch that supplies
        // only one of the packagingUnit/packagingSize pair is fine so long as the product
        // already carries the other, and a patch that clears one (a blank packagingUnit
        // resolves to null above) while leaving the other in place is exactly as ambiguous as
        // never having set both together. Likewise a patch that clears unitOfMeasure while
        // packaging is still in place from before is rejected by the second check, exactly as
        // if packaging had been sent alone from the start.
        requirePackagingUnitAndSizePaired(product.getPackagingUnit(), product.getPackagingSize());
        requirePackagingImpliesUnitOfMeasure(
                product.getUnitOfMeasure(), product.getPackagingUnit(), product.getPackagingSize());
        // V19: no companyVendorId/clearCompanyVendor handling here any more - see
        // UpdateProductRequest's class javadoc. Vendor management moved to
        // companyvendor.ProductVendorService.

        List<String> warnings = new ArrayList<>();
        if (hasContent(image)) {
            applyImage(product, image, warnings);
        } else if (Boolean.TRUE.equals(request.removeImage())) {
            product.setImageUrl(null);
        }

        // Last, after the image has been applied, because a swapped photo is one of the
        // edits that most obviously invalidates a review. Price, cost, stock threshold
        // and the active flag are all changed above and deliberately do NOT reach this
        // check - see ProductModerationRules for the ruling and its reasoning.
        // packagingSize/packagingUnit join unitOfMeasure here as of the V18 split: a 25kg bag
        // quietly becoming a 50kg one, or a "Bag" quietly becoming a "Carton" at the same
        // unitOfMeasure and size, are both exactly the kind of identity change the other five
        // fields are guarded against.
        if (ProductModerationRules.invalidatesApproval(
                beforeName, product.getName(),
                beforeSku, product.getSku(),
                beforeDescription, product.getDescription(),
                beforeBrand, product.getBrand(),
                beforeImageUrl, product.getImageUrl(),
                beforeUnitOfMeasure, product.getUnitOfMeasure(),
                beforePackagingUnit, product.getPackagingUnit(),
                beforePackagingSize, product.getPackagingSize())) {
            productModerationService.onListingContentChanged(product);
        }

        String preferredVendorName = productVendorRepository
                .findByClientIdAndProductIdAndIsPreferredTrue(product.getClientId(), id)
                .map(vendor -> vendor.getCompanyVendor().getName())
                .orElse(null);
        return ProductResponse.from(product, preferredVendorName, warnings.isEmpty() ? null : warnings);
    }

    @Transactional
    public void deactivate(UUID id) {
        findTenantProductOrThrow(id).setActive(false);
    }

    /**
     * Undoes {@link #deactivate}. Deactivation is a soft delete - the row, its stock
     * ledger and its supplier lines all survive it - so the product simply stops
     * appearing in active lists, and there was until now no way back short of an
     * {@code active: true} on the multipart PUT, which means re-submitting the whole
     * form (and, for a seller, re-satisfying {@link UnitPriceRequiredException}) just
     * to flip one boolean.
     *
     * <h2>Deliberately does not touch approval status</h2>
     * {@code active} is not one of the fields {@code ProductModerationRules
     * .invalidatesApproval} watches, and that is the correct reading rather than an
     * omission: nothing a buyer sees about the listing changed while it was away, so
     * a product that was APPROVED before it was deactivated is APPROVED again the
     * moment it comes back, and one that was PENDING or REJECTED stays exactly where
     * it was in the queue. Deactivating and reactivating is therefore not a way to
     * launder a rejected listing back into the catalog.
     *
     * <h2>Idempotent, and no error for an already-active product</h2>
     * Reactivating something already active is a no-op that answers 204, matching
     * {@link #deactivate}'s own tolerance of a second call. "Make sure this is on" is
     * what the caller means, and failing the second click of a button whose first
     * click succeeded would be a worse answer than doing nothing.
     */
    @Transactional
    public void activate(UUID id) {
        findTenantProductOrThrow(id).setActive(true);
    }

    /**
     * <h2>Why the export now carries the vendor columns too</h2>
     * The export and the template are deliberately the same column set, because the most common
     * thing a tenant does with these two files is "export what we have, edit it, upload it back".
     * A column present on the template but absent from the export is a column that silently
     * empties itself on every round trip - and since {@code vendor_name} now maintains the
     * product's supplier line on import, an export without it would mean re-uploading your own
     * catalog wipes its vendor attribution. Filled from the SAME batched preferred-vendor query
     * {@link #list} uses, for the N+1 reason {@link #preferredVendorNamesFor} states.
     */
    @Transactional(readOnly = true)
    public byte[] exportActiveProducts() {
        UUID tenantId = requireTenantId();
        List<Product> products = productRepository.findAll(ProductSpecifications.forTenant(tenantId, null, true));
        // The seller flag is resolved here rather than inside ProductExcelService for the reason
        // generateTemplate states, and for the same reason the two files must agree on it: the
        // export IS the template once it has your products in it.
        Client owner = sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
        return productExcelService.exportProducts(
                products, preferredVendorSnapshotsFor(tenantId, products), owner != null && owner.canSell());
    }

    /**
     * The three exported vendor columns per product, flattened off the same one-query-per-page
     * read {@link #preferredVendorNamesFor} performs. Only the PREFERRED line is exported: a
     * spreadsheet row is one product, and a product with four suppliers cannot put four of them
     * in one cell. The multi-vendor case round-trips through the continuation-row convention
     * (BULK_IMPORT_DESIGN.md section 7.1) on the way IN; exporting it that way as well would mean
     * an export whose row count does not match the catalog's product count, which is a surprise
     * nobody asked for.
     */
    private Map<UUID, ProductVendorSnapshot> preferredVendorSnapshotsFor(UUID tenantId, List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        List<ProductVendor> preferred = productVendorRepository.findPreferredByClientIdAndProductIdIn(tenantId, productIds);
        // Batched, same N+1-avoidance reasoning findPreferredByClientIdAndProductIdIn already
        // states for itself - vendorSku moved onto ProductVendorPack (V24) and is no longer a
        // field this join fetches directly.
        // Collectors.toMap rejects a null VALUE (Objects.requireNonNull inside its accumulator),
        // and a pack's vendorSku is routinely null - the common case is a vendor line with no
        // code recorded at all - so this collects by hand rather than via toMap.
        Map<UUID, String> vendorSkuByVendorId = new HashMap<>();
        Map<UUID, BigDecimal> lastCostByVendorId = new HashMap<>();
        for (ProductVendorPack pack : productVendorPackRepository
                .findAllByProductVendorIdInAndIsDefaultTrue(preferred.stream().map(ProductVendor::getId).toList())) {
            vendorSkuByVendorId.put(pack.getProductVendor().getId(), pack.getVendorSku());
            lastCostByVendorId.put(pack.getProductVendor().getId(), pack.getLastCostPrice());
        }
        return preferred.stream()
                .collect(Collectors.toMap(
                        vendor -> vendor.getProduct().getId(),
                        vendor -> new ProductVendorSnapshot(
                                vendor.getCompanyVendor().getName(), vendorSkuByVendorId.get(vendor.getId()),
                                vendor.isPreferred(), lastCostByVendorId.get(vendor.getId()))));
    }

    /**
     * Resolves the caller's seller status and delegates - keeps ProductExcelService free of
     * tenant/security concerns (see its class javadoc) by handing it a plain boolean instead
     * of letting it reach into SellerDirectory/TenantContext itself. A company's template has
     * no unit_price column at all; a seller's keeps it as a required column.
     */
    @Transactional(readOnly = true)
    public byte[] generateTemplate() {
        UUID tenantId = requireTenantId();
        Client owner = sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
        boolean isSeller = owner != null && owner.canSell();
        // V20: the template is per-tenant in a second way now. The tenant's own supplier names go
        // into the vendor_name dropdown (BULK_IMPORT_DESIGN.md section 5.2) - a user who picks
        // "Dangote Nigeria Plc" off a list never generates an unresolved-vendor row for it, which
        // is a question they never get asked and a repair they never have to make. The lookup
        // lives here, not in ProductExcelService, for the same reason isSeller does: that class
        // stays free of tenant and security concerns and is handed plain data instead.
        List<String> vendorNames = companyVendorRepository
                .findAllByClientIdAndActiveTrueOrderByNameAsc(tenantId)
                .stream()
                .map(CompanyVendor::getName)
                .toList();
        List<String> categoryNames = companyCategoryRepository.findAllByClientIdOrderByNameAsc(tenantId).stream()
                .map(com.procurepal_services.stock_bridge_api.entity.CompanyCategory::getName)
                .toList();
        return productExcelService.generateTemplate(new ProductTemplateContext(
                isSeller, vendorNames, productSkuSettingsService.isEnabled(tenantId), categoryNames));
    }

    private void applyImage(Product product, MultipartFile image, List<String> warnings) {
        UploadResult result = s3ImageService.uploadProductImage(image);
        if (result.success()) {
            product.setImageUrl(result.url());
        } else {
            warnings.add("Image upload failed: " + result.failureReason() + " - product saved without an image.");
        }
    }

    private void assertSkuAvailable(UUID tenantId, String sku, UUID excludingProductId) {
        productRepository.findByClientIdAndSku(tenantId, sku).ifPresent(existing -> {
            if (!existing.getId().equals(excludingProductId)) {
                throw new SkuTakenException(sku);
            }
        });
    }

    /** V23's escape hatch for {@link #update} - see {@code SkuOverrideNotPermittedException} and the migration's role rationale. */
    private boolean hasSkuOverrideAuthority(AuthenticatedUserPrincipal principal) {
        return principal != null
                && principal.getAuthorities().stream()
                        .anyMatch(granted -> "PRODUCT_SKU_OVERRIDE".equals(granted.getAuthority()));
    }

    /**
     * Validates a submitted unit-of-measure code against the fixed catalog and normalizes it
     * to that catalog's CODE (never its display label) for storage - "kg", "Kg" and "KG" must
     * all land on the same stored value, or two callers describing the same unit would produce
     * rows that read as different products. Also requires the resolved code's
     * {@link com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure#role()} to be
     * {@link UnitOfMeasureRole#BASE} - see that enum for why a product's {@code unitOfMeasure}
     * and {@code packagingUnit} draw from disjoint halves of the same catalog. A code that
     * exists but is PACKAGING-role (e.g. "BAG" submitted here) is rejected with the same
     * message as a code that does not exist at all - from this field's perspective both are
     * simply "not a valid unit of measure".
     *
     * <p>Blank or null is "not provided" and returns null rather than a validation failure -
     * matching how {@link UnitOfMeasure#fromCode} itself treats blank input, and matching how
     * brand/unitOfMeasure have always been cleared on the marketplace-details route (an empty
     * string clears the field). It is {@link #requirePackagingImpliesUnitOfMeasure} that decides
     * whether a null result here is actually acceptable at the call site.
     */
    private String resolveUnitOfMeasure(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return UnitOfMeasure.fromCode(code)
                .filter(unit -> unit.canServeAs(UnitOfMeasureRole.BASE))
                .map(UnitOfMeasure::code)
                .orElseThrow(() -> new InvalidUnitOfMeasureException(code));
    }

    /**
     * {@code resolveUnitOfMeasure}'s counterpart for {@code packagingUnit}: same normalization,
     * same blank-is-null treatment, same "not on the list" handling - but requires
     * {@link UnitOfMeasureRole#PACKAGING} instead of {@code BASE}, and names the field
     * "pack" in the exception message (UNIT_UX_CONTRACT.md section 1) so a caller can tell the two validation
     * failures apart even though they share one exception class.
     */
    private String resolvePackagingUnit(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return UnitOfMeasure.fromCode(code)
                .filter(unit -> unit.canServeAs(UnitOfMeasureRole.PACKAGING))
                .map(UnitOfMeasure::code)
                .orElseThrow(() -> new InvalidUnitOfMeasureException(code, "pack"));
    }

    /**
     * packagingUnit and packagingSize describe one fact together - how much one packaging unit
     * actually holds - so a product may never end up with exactly one of the two set. Takes
     * the RESULTING pair - after whatever a create or update request supplied has already been
     * applied - not the request's raw fields, which is what makes this correct for update()'s
     * patch semantics: a request supplying only one of the pair is fine as long as the product
     * already carries the other from before.
     */
    private void requirePackagingUnitAndSizePaired(String packagingUnit, BigDecimal packagingSize) {
        if ((packagingUnit == null) != (packagingSize == null)) {
            throw new PackagingUnitAndSizeRequiredTogetherException();
        }
    }

    /**
     * packagingSize is a COUNT of unitOfMeasure, so packaging may never be set on a product
     * with no unitOfMeasure to quantify - see {@link PackagingRequiresUnitOfMeasureException}.
     * One-directional, unlike {@link #requirePackagingUnitAndSizePaired}: unitOfMeasure may
     * always stand alone (a product sold loose), it is only packaging that requires it. Takes
     * the RESULTING state, same patch-safe timing as the pairing check - called after that
     * check has already confirmed packagingUnit/packagingSize are both-or-neither, so testing
     * either one here is equivalent, but both are named for readability at the call sites.
     */
    private void requirePackagingImpliesUnitOfMeasure(
            String unitOfMeasure, String packagingUnit, BigDecimal packagingSize) {
        if (unitOfMeasure == null && (packagingUnit != null || packagingSize != null)) {
            throw new PackagingRequiresUnitOfMeasureException();
        }
        // The invariant the BASE/PACKAGING split used to guarantee by accident. Now that COUNT
        // units serve either role (UnitOfMeasure.canServeAs), "a Piece of 34 Pieces" is
        // expressible and has to be refused explicitly - a pack of itself converts nothing.
        if (packagingUnit != null && packagingUnit.equalsIgnoreCase(unitOfMeasure)) {
            throw new PackagingUnitSameAsStockUnitException(
                    UnitOfMeasure.fromCode(packagingUnit).map(UnitOfMeasure::label).orElse(packagingUnit));
        }
    }

    private boolean hasContent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    // V19: resolveVendor(UUID companyVendorId) removed along with products.company_vendor_id.
    // The equivalent tenant-scoping lookup for a supplier id now lives in
    // ProductVendorService.findOrCreateForReceipt (via CompanyVendorLookup, same pattern this
    // method used) and in CreateProductRequest.InitialVendor's own resolution through
    // StockManagementService.stockIn.

    private Product findTenantProductOrThrow(UUID id) {
        return productRepository.findByIdForCurrentTenant(id).orElseThrow(ProductNotFoundException::new);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }

}
