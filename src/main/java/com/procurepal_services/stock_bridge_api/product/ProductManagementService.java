package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorLookup;
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
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
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

        Product product = Product.builder()
                .name(request.name())
                .sku(request.sku())
                .description(request.description())
                .unitPrice(request.unitPrice())
                .costPrice(request.costPrice())
                .lowStockThreshold(request.lowStockThreshold())
                .companyVendor(resolveVendor(request.companyVendorId()))
                .active(true)
                // Stated rather than left to the column's PENDING default, so the
                // platform owner's auto-approval is a rule somebody can find. For a
                // vendor this is PENDING and the product enters the moderation queue;
                // for an ordinary buying company it is also PENDING and is never read
                // by anything - see ProductModerationRules.
                .approvalStatus(ProductModerationRules.initialStatusFor(
                        sellerDirectory.findSellerOfRecord(tenantId).orElse(null)))
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

        // Snapshot the identity fields BEFORE any mutation, so the moderation check at
        // the bottom compares what the listing was against what it became. Captured
        // even for tenants that are not sellers - it is six string reads, and making it
        // conditional would mean two code paths through this method.
        String beforeName = product.getName();
        String beforeSku = product.getSku();
        String beforeDescription = product.getDescription();
        String beforeBrand = product.getBrand();
        String beforeImageUrl = product.getImageUrl();
        String beforeUnitOfMeasure = product.getUnitOfMeasure();

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
            product.setUnitPrice(request.unitPrice());
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
        if (ProductModerationRules.invalidatesApproval(
                beforeName, product.getName(),
                beforeSku, product.getSku(),
                beforeDescription, product.getDescription(),
                beforeBrand, product.getBrand(),
                beforeImageUrl, product.getImageUrl(),
                beforeUnitOfMeasure, product.getUnitOfMeasure())) {
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
        List<ParsedProductRow> parsedRows = productExcelService.parse(file);

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

        List<Product> products = parsedRows.stream().map(this::toNewProduct).toList();
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
     */
    private Product toNewProduct(ParsedProductRow row) {
        return Product.builder()
                .name(row.name())
                .sku(row.sku())
                .description(row.description())
                .unitPrice(row.unitPrice())
                .costPrice(row.costPrice())
                .quantityOnHand(row.quantityOnHand())
                .lowStockThreshold(row.lowStockThreshold())
                .active(true)
                .approvalStatus(ProductModerationRules.initialStatusFor(
                        sellerDirectory.findSellerOfRecord(requireTenantId()).orElse(null)))
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
