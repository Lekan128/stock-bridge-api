package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationService;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadValidationException;
import com.procurepal_services.stock_bridge_api.product.bulk.ParsedProductRow;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductExcelService;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductTemplateContext;
import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import com.procurepal_services.stock_bridge_api.imports.ImportCommitExecutor;
import com.procurepal_services.stock_bridge_api.imports.ImportSessionService;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.io.ImportLimits;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductVendorSnapshot;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductRowError;
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
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final ImportSessionService importSessionService;
    private final ImportCommitExecutor importCommitExecutor;
    private final ProductSkuSettingsService productSkuSettingsService;
    private final SkuGenerationService skuGenerationService;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, Boolean active, Pageable pageable) {
        UUID tenantId = requireTenantId();
        Page<Product> page = productRepository.findAll(ProductSpecifications.forTenant(tenantId, search, active), pageable);
        Map<UUID, String> preferredVendorNames = preferredVendorNamesFor(tenantId, page.getContent());
        return page.map(product -> ProductResponse.from(product, preferredVendorNames.get(product.getId()), null));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        UUID tenantId = requireTenantId();
        Product product = findTenantProductOrThrow(id);
        String preferredVendorName = productVendorRepository
                .findByClientIdAndProductIdAndIsPreferredTrue(tenantId, id)
                .map(vendor -> vendor.getCompanyVendor().getName())
                .orElse(null);
        return ProductResponse.from(product, preferredVendorName, null);
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
                .build();

        List<String> warnings = new ArrayList<>();
        if (hasContent(image)) {
            applyImage(product, image, warnings);
        }

        // Flushed, not merely saved: request.initialVendor() below (when present) writes a
        // StockMovement through StockManagementService.stockIn in the SAME transaction, and
        // that service locks the product row with a SELECT ... FOR UPDATE against the database
        // - it must already be able to find this row. Same reasoning
        // IncomingStockService.findOrCreateBuyerProduct's own saveAndFlush documents.
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
            if (!Objects.equals(resolvedUnitOfMeasure, product.getUnitOfMeasure())
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
        return productExcelService.exportProducts(products, preferredVendorSnapshotsFor(tenantId, products));
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
        return productVendorRepository.findPreferredByClientIdAndProductIdIn(tenantId, productIds).stream()
                .collect(Collectors.toMap(
                        vendor -> vendor.getProduct().getId(),
                        vendor -> new ProductVendorSnapshot(
                                vendor.getCompanyVendor().getName(), vendor.getVendorSku(), vendor.isPreferred())));
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
        return productExcelService.generateTemplate(
                new ProductTemplateContext(isSeller, vendorNames, productSkuSettingsService.isEnabled(tenantId)));
    }

    /**
     * V1: creates only, all-or-nothing. ProductExcelService.parse() already
     * rejects the file (with the full set of header/row errors) if any row is
     * structurally invalid or duplicates a sku within the file itself; this
     * method adds one more pass checking each remaining candidate row against
     * existing tenant products, so a row that looks fine on its own can still
     * be rejected for reusing a sku that's already in the catalog. The two
     * passes aren't merged into a single error list - if the file has
     * structural errors, the DB pass never runs, so a user fixing those errors
     * could still hit a sku conflict on their next attempt. That's an
     * acceptable tradeoff for V1's fail-fast simplicity over a more complete
     * "all known errors in one response" experience; partial-success (mode b)
     * was not implemented for the same reason - simpler for a user cleaning up
     * a spreadsheet to reason about "nothing happened, fix these" than
     * reconciling which rows silently landed and which didn't.
     *
     * <h2>V20: this is now a shim, and what that did and did not change</h2>
     * BULK_IMPORT_DESIGN.md section 10 keeps this endpoint but reimplements it as
     * {@code POST /api/imports} plus an immediate commit with CREATE_ONLY, so nothing that
     * already integrates breaks while there stops being a second way to create a product from a
     * spreadsheet. The response shape is unchanged, and validation failures still arrive as
     * {@code BulkUploadValidationException} carrying {@code List<ProductRowError>}.
     *
     * <p>The write really does go through the engine now, which is what matters: one code path
     * stamps {@code import_batch_id}, one writes the opening-balance {@code StockMovement} that
     * non-negotiable 1 of contract section 8 requires (<b>no quantity reaches {@code
     * products.quantity_on_hand} without a movement</b>, on every path including this one), and
     * an upload made here is undoable from the imports screen exactly like any other.
     *
     * <h2>Why validation still runs through the M2 parser first</h2>
     * A deliberate, and the only, deviation from a pure shim. This endpoint's error <em>copy</em>
     * is part of its frozen interface: {@code ProductBulkImportExportIntegrationTest} asserts on
     * {@code "is required"} exactly, on {@code "not a recognized unit of measure"}, and on
     * messages that name {@code unit_of_measure} and {@code packaging_size} as their subject.
     * The review grid's copy is bound by the opposite rule - design 9.6 forbids a column name as
     * an error subject and requires a sentence a person would say - so the two vocabularies
     * genuinely cannot be the same strings. Routing validation through the engine and translating
     * its verdicts back would mean maintaining a second, invisible copy deck whose only consumer
     * is a test, and getting one of eleven mappings wrong would break a caller silently.
     *
     * <p>There is also a substantive difference the translation could not paper over: for a
     * buying company the engine has no {@code unit_price} field at all (contract section 5 omits
     * it for a non-seller), while this endpoint has always parsed and rejected a malformed value
     * in that column whoever uploaded it.
     *
     * <p>So: the parser decides whether the file is acceptable, in the words it has always used;
     * the engine decides what happens to it. The cost is that the file is read twice, which is a
     * fair price on a compatibility path.
     */
    @Transactional
    public BulkUploadResponse bulkUpload(MultipartFile file) {
        return bulkUpload(file, null);
    }

    /**
     * @param actingUserId attributed on the opening-balance {@code StockMovement} each row with
     *     quantity now writes - see {@code StockMovement.createdBy}, and {@link #create(
     *     CreateProductRequest, MultipartFile, UUID)}, which took the same overload shape for
     *     the same reason when V19 gave product creation a ledger write. V1's own {@code
     *     stock_movements} comment already anticipated this: {@code created_by} is nullable
     *     "to support bulk imports".
     */
    @Transactional
    public BulkUploadResponse bulkUpload(MultipartFile file, UUID actingUserId) {
        UUID tenantId = requireTenantId();

        // One lookup answers two questions, same as create(): whether unit_price is a required
        // column/cell for THIS upload (ProductExcelService.parse needs to know before it even
        // validates headers), and - through the engine below - the moderation stamp every
        // created row gets.
        Client owner = sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
        boolean isSeller = owner != null && owner.canSell();
        boolean skuAutoGenerated = productSkuSettingsService.isEnabled(tenantId);

        // Phase one: the frozen validation. See the method javadoc for why this still runs
        // through M2's parser rather than through the engine's own validation pass.
        List<ParsedProductRow> parsedRows = productExcelService.parse(file, isSeller, skuAutoGenerated);
        // Moot, and skipped outright, when auto-generated: parse() above never read a sku cell
        // (row.sku() is null on every row), so there is nothing here to check against the
        // catalog - the server's own generated values are checked for collisions later, inside
        // SkuGenerationService, not against a value that was never supplied.
        if (!skuAutoGenerated) {
            List<ProductRowError> duplicateSkuErrors = new ArrayList<>();
            for (ParsedProductRow row : parsedRows) {
                if (productRepository.findByClientIdAndSku(tenantId, row.sku()).isPresent()) {
                    duplicateSkuErrors.add(new ProductRowError(
                            row.excelRow(), "sku", "SKU already exists in your product catalog"));
                }
            }
            if (!duplicateSkuErrors.isEmpty()) {
                throw new BulkUploadValidationException(duplicateSkuErrors);
            }
        }

        // Phase two: the write, through the session engine. Design 10's "POST /api/imports +
        // immediate commit with CREATE_ONLY", so there is exactly one code path that creates a
        // product from a spreadsheet row, one that stamps import_batch_id, and one that writes
        // the opening-balance movement design 3 requires.
        ImportSessionResponse session =
                importSessionService.create(file, ImportKind.PRODUCT_CATALOG, ImportMode.CREATE_ONLY, actingUserId);
        if (session.status() != ImportStatus.READY) {
            importSessionService.discard(session.id());
            throw new BulkUploadValidationException(engineErrors(session.id()));
        }

        // Always synchronous, whatever the row count. This endpoint has answered with the
        // created products since the day it shipped and its callers are written against that;
        // handing back a 202 they have no way to poll would break them far more thoroughly than
        // a slow response. Running inline also keeps the whole shim inside one transaction,
        // which is what preserves the "nothing happened, fix these" semantics its callers expect.
        importCommitExecutor.runNow(importSessionService.beginCommit(session.id(), actingUserId));

        List<Product> created = productRepository.findAllByClientIdAndImportBatchId(tenantId, session.id()).stream()
                .sorted(java.util.Comparator.comparing(Product::getSku))
                .toList();
        return new BulkUploadResponse(
                created.size(), created.stream().map(ProductResponse::from).toList());
    }

    /**
     * Whatever the engine objected to, in the legacy error shape.
     *
     * <p>Unreachable in practice - phase one has already accepted the file, and the engine's
     * validation is a superset of nothing that the parser rejects - but a shim that silently
     * reported success because it could not translate a failure would be far worse than one that
     * reports the failure in slightly unfamiliar words. The prose here is the review grid's, not
     * the parser's, which is honest about where the objection came from.
     */
    private List<ProductRowError> engineErrors(UUID sessionId) {
        List<ProductRowError> errors = new ArrayList<>();
        for (ImportRowResponse row : importSessionService
                .rows(sessionId, "ERROR", org.springframework.data.domain.Pageable.ofSize(ImportLimits.MAX_ROWS))
                .getContent()) {
            for (ImportRowResponse.Error error : row.errors()) {
                errors.add(new ProductRowError(row.excelRow(), error.column(), error.message()));
            }
        }
        if (errors.isEmpty()) {
            errors.add(new ProductRowError(1, "file", "We could not import this file. Please check it and try again."));
        }
        return errors;
    }


    /**
     * The tenant's active suppliers keyed by name, built once per upload and only when the file
     * used the {@code vendor_name} column at all.
     *
     * <h2>Exact (case- and space-insensitive) matching only, on purpose</h2>
     * No fuzzy matching here, and that is not an omission. "Dangote Ltd" against a directory
     * holding "Dangote Nigeria Plc" is a genuine question - it might be the same company, it might
     * be a second supplier with a similar name - and BULK_IMPORT_DESIGN.md section 6.4 puts that
     * question on the review screen, asked once per distinct value with suggestions ranked and a
     * "create new supplier" option beside them. Guessing it here would attach a delivery to the
     * wrong company's ledger silently, which is precisely the kind of invented fact section 9.6
     * forbids. The compatibility path has no review screen to ask on, so an unmatched name simply
     * leaves the movement unattributed - the same "the spreadsheet didn't say" outcome as before.
     *
     * <p>Later duplicates lose to the first, matching the alphabetical order the directory read
     * returns: two active vendors with names differing only in casing is a data-entry accident,
     * and picking the first one deterministically beats picking whichever the map happened to
     * hold.
     */

    /**
     * Case-folded, whitespace-collapsed - so "dangote nigeria plc" and "Dangote  Nigeria Plc"
     * match the directory entry a user copied out of it by hand. Anything beyond that (dropping
     * "Ltd", stemming, edit distance) is the review screen's job, for the reason
     * {@link #openingBalanceVendorId} states.
     */

    /**
     * Same moderation stamp as {@link #create}. Bulk upload is exactly the path a vendor
     * with a large catalogue uses, so leaving it on the column default would be the one
     * way to get several hundred unmoderated listings in at once - and, conversely, it
     * is why ProcurePal's own bulk imports must still come out APPROVED rather than
     * filling the operator's queue with its own spreadsheet.
     *
     * <p>{@code isSeller}/{@code owner} are the single lookup {@link #bulkUpload} already made
     * - not re-resolved here - matching create()'s "one lookup answers two questions" comment.
     * A non-seller's row.unitPrice() is discarded exactly like create() discards a stale
     * unitPrice from a non-seller request; a seller's row is guaranteed non-null here because
     * ProductExcelService.parse() already rejected the file otherwise, so no defensive re-check
     * is needed. row.unitOfMeasure()/row.packagingUnit()/row.packagingSize() have already been
     * validated, role-checked and cross-validated by parse() - persisted as-is, not re-validated.
     *
     * <h2>V20: quantityOnHand starts at zero here, whatever the row said</h2>
     * {@code row.quantityOnHand()} is deliberately NOT copied onto the product. It is applied
     * through the ledger by {@code ProductCatalogRowHandler}'s opening-balance pass, which brings
     * the counter to the row's number as a consequence of a real {@code StockMovement} rather
     * than as an assertion nothing backs - a quantity with no movement behind it has no vendor,
     * no cost basis, no delivery date, and writes no allocation row when it is later sold.
     *
     * <p>{@code costPrice} is still seeded here rather than left to the ledger. That is not an
     * inconsistency: a row may carry a cost with no quantity ("this is what we pay for it, we
     * just have none right now"), and there is no movement for that case to hang a cost on. When
     * the row DOES carry quantity, {@code stockIn}'s weighted-average recalculation runs against
     * a product with zero on hand and simply returns the delivery's own price - the same value,
     * arrived at honestly.
     */

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
