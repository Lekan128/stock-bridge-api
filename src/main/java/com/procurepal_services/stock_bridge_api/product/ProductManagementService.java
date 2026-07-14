package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
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
 */
@Service
@RequiredArgsConstructor
public class ProductManagementService {

    private final ProductRepository productRepository;
    private final S3ImageService s3ImageService;
    private final ProductExcelService productExcelService;

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
                .active(true)
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

        List<String> warnings = new ArrayList<>();
        if (hasContent(image)) {
            applyImage(product, image, warnings);
        } else if (Boolean.TRUE.equals(request.removeImage())) {
            product.setImageUrl(null);
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
