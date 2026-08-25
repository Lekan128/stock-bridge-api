package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorLookup;
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
import com.procurepal_services.stock_bridge_api.product.bulk.ProductRowError;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private final ProductRepository productRepository;
    private final S3ImageService s3ImageService;
    private final ProductExcelService productExcelService;
    private final SellerDirectory sellerDirectory;
    private final ProductModerationService productModerationService;
    /**
     * Resolves a supplier id from a request against the caller's OWN directory.
     * Goes through the shared lookup rather than the repository directly so the
     * "active, and belongs to this tenant" check has one implementation - a vendor
     * id arriving in a request body is exactly where a slightly-wrong copy of it
     * would file another company's supplier against this company's stock.
     */
    private final CompanyVendorLookup companyVendorLookup;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, Boolean active, Pageable pageable) {
        return productRepository
                .findAll(ProductSpecifications.forTenant(requireTenantId(), search, active), pageable)
                .map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.from(findTenantProductOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> lowStock() {
        return productRepository.findLowStockByClientId(requireTenantId()).stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request, MultipartFile image) {
        UUID tenantId = requireTenantId();
        assertSkuAvailable(tenantId, request.sku(), null);

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

        Product product = Product.builder()
                .name(request.name())
                .sku(request.sku())
                .description(request.description())
                .unitPrice(isSeller ? request.unitPrice() : null)
                .costPrice(request.costPrice())
                .lowStockThreshold(request.lowStockThreshold())
                .companyVendor(resolveVendor(request.companyVendorId()))
                .unitOfMeasure(unitOfMeasure)
                .packagingUnit(packagingUnit)
                .packagingSize(request.packagingSize())
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

        product = productRepository.save(product);
        return ProductResponse.from(product, warnings.isEmpty() ? null : warnings);
    }

    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request, MultipartFile image) {
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
        if (request.costPrice() != null) {
            product.setCostPrice(request.costPrice());
        }
        if (request.lowStockThreshold() != null) {
            product.setLowStockThreshold(request.lowStockThreshold());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        if (request.unitOfMeasure() != null) {
            product.setUnitOfMeasure(resolveUnitOfMeasure(request.unitOfMeasure()));
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
        // Unlink wins over relink: two contradictory instructions in one body, and
        // "clear it" is the unambiguous one. See UpdateProductRequest for why a bare
        // null companyVendorId cannot mean this.
        if (Boolean.TRUE.equals(request.clearCompanyVendor())) {
            product.setCompanyVendor(null);
        } else if (request.companyVendorId() != null) {
            product.setCompanyVendor(resolveVendor(request.companyVendorId()));
        }

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

        return ProductResponse.from(product, warnings.isEmpty() ? null : warnings);
    }

    @Transactional
    public void deactivate(UUID id) {
        findTenantProductOrThrow(id).setActive(false);
    }

    @Transactional(readOnly = true)
    public byte[] exportActiveProducts() {
        List<Product> products =
                productRepository.findAll(ProductSpecifications.forTenant(requireTenantId(), null, true));
        return productExcelService.exportProducts(products);
    }

    /**
     * Resolves the caller's seller status and delegates - keeps ProductExcelService free of
     * tenant/security concerns (see its class javadoc) by handing it a plain boolean instead
     * of letting it reach into SellerDirectory/TenantContext itself. A company's template has
     * no unit_price column at all; a seller's keeps it as a required column.
     */
    @Transactional(readOnly = true)
    public byte[] generateTemplate() {
        Client owner = sellerDirectory.findSellerOfRecord(requireTenantId()).orElse(null);
        boolean isSeller = owner != null && owner.canSell();
        return productExcelService.generateTemplate(isSeller);
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
     */
    @Transactional
    public BulkUploadResponse bulkUpload(MultipartFile file) {
        UUID tenantId = requireTenantId();

        // One lookup answers two questions, same as create(): whether unit_price is a
        // required column/cell for THIS upload (ProductExcelService.parse needs to know
        // before it even validates headers) and the moderation stamp every created row
        // gets below. Resolved here, at the top, rather than inside toNewProduct, because
        // parse() itself now depends on isSeller.
        Client owner = sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
        boolean isSeller = owner != null && owner.canSell();

        List<ParsedProductRow> parsedRows = productExcelService.parse(file, isSeller);

        List<ProductRowError> duplicateSkuErrors = new ArrayList<>();
        for (ParsedProductRow row : parsedRows) {
            if (productRepository.findByClientIdAndSku(tenantId, row.sku()).isPresent()) {
                duplicateSkuErrors.add(
                        new ProductRowError(row.excelRow(), "sku", "SKU already exists in your product catalog"));
            }
        }
        if (!duplicateSkuErrors.isEmpty()) {
            throw new BulkUploadValidationException(duplicateSkuErrors);
        }

        List<Product> products =
                parsedRows.stream().map(row -> toNewProduct(row, isSeller, owner)).toList();
        List<Product> saved = productRepository.saveAll(products);
        return new BulkUploadResponse(
                saved.size(), saved.stream().map(ProductResponse::from).toList());
    }

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
     */
    private Product toNewProduct(ParsedProductRow row, boolean isSeller, Client owner) {
        return Product.builder()
                .name(row.name())
                .sku(row.sku())
                .description(row.description())
                .unitPrice(isSeller ? row.unitPrice() : null)
                .costPrice(row.costPrice())
                .quantityOnHand(row.quantityOnHand())
                .lowStockThreshold(row.lowStockThreshold())
                .unitOfMeasure(row.unitOfMeasure())
                .packagingUnit(row.packagingUnit())
                .packagingSize(row.packagingSize())
                .active(true)
                .approvalStatus(ProductModerationRules.initialStatusFor(owner))
                .build();
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
                .filter(unit -> unit.role() == UnitOfMeasureRole.BASE)
                .map(UnitOfMeasure::code)
                .orElseThrow(() -> new InvalidUnitOfMeasureException(code));
    }

    /**
     * {@code resolveUnitOfMeasure}'s counterpart for {@code packagingUnit}: same normalization,
     * same blank-is-null treatment, same "not on the list" handling - but requires
     * {@link UnitOfMeasureRole#PACKAGING} instead of {@code BASE}, and names the field
     * "packaging unit" in the exception message so a caller can tell the two validation
     * failures apart even though they share one exception class.
     */
    private String resolvePackagingUnit(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return UnitOfMeasure.fromCode(code)
                .filter(unit -> unit.role() == UnitOfMeasureRole.PACKAGING)
                .map(UnitOfMeasure::code)
                .orElseThrow(() -> new InvalidUnitOfMeasureException(code, "packaging unit"));
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
    }

    private boolean hasContent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    /**
     * The supplier a buyer picked, checked against their own directory.
     *
     * <p>Nothing stops a caller sending a well-formed UUID belonging to another
     * company, and {@code products.company_vendor_id} has no CHECK that could catch
     * it - a foreign key alone is satisfied by any real vendor row. This lookup IS
     * the constraint, and it is why the id is resolved rather than assigned.
     *
     * <p>A VERIFIED entry is a legitimate manual choice, not just an automatic one:
     * a company that buys rice from a marketplace seller may also want a product
     * they added by hand filed under that seller. What they may not do is EDIT the
     * entry, which is a different question and is refused elsewhere.
     */
    private CompanyVendor resolveVendor(UUID companyVendorId) {
        if (companyVendorId == null) {
            return null;
        }
        return companyVendorLookup.find(companyVendorId).orElseThrow(InvalidProductVendorException::new);
    }

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
