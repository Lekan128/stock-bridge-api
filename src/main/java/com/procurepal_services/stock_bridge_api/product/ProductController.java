package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.product.sku.dto.ProductSkuSettingsResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.SkuPreviewResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.UpdateProductSkuSettingsRequest;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tenant-scoped product catalog - see ProductManagementService for isolation
 * and image-upload handling.
 *
 * Authorized per method rather than per class because reading the catalog and
 * changing it are separate permissions: VIEW_PRODUCTS is held by every role
 * (a finance officer needs to see what things cost; a storekeeper needs to
 * find the item they're counting), while MANAGE_PRODUCTS is what actually
 * writes to it. The import template and bulk upload count as writing - the
 * template exists only to be filled in and posted back.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductManagementService productManagementService;

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<byte[]> template() {
        return xlsxResponse(productManagementService.generateTemplate(), "product-import-template.xlsx");
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ResponseEntity<byte[]> export() {
        return xlsxResponse(productManagementService.exportActiveProducts(), "products-export.xlsx");
    }

    /**
     * MANAGE_PRODUCTS, not VIEW_PRODUCTS: unlike the company profile's read side, this is
     * generation CONFIGURATION - relevant to whoever might change it or the create-product form,
     * not to every role that can merely see the catalog.
     */
    @GetMapping("/sku-settings")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ProductSkuSettingsResponse getSkuSettings() {
        return productManagementService.getSkuSettings();
    }

    @PutMapping("/sku-settings")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ProductSkuSettingsResponse updateSkuSettings(@Valid @RequestBody UpdateProductSkuSettingsRequest request) {
        return productManagementService.updateSkuSettings(request);
    }

    /**
     * A non-committing peek - see {@code SkuGenerationService.preview}. Called once per
     * create-product form load, not per keystroke: the response's {@code nextSequence} is the
     * only part of the preview the client cannot compute itself, and the create form re-renders
     * the displayed SKU locally as the product name changes, against the pattern it already has
     * from {@code GET /api/products/sku-settings}.
     */
    @GetMapping("/sku-preview")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public SkuPreviewResponse previewSku() {
        return productManagementService.previewSku();
    }

    /**
     * V20: calls the 2-arg {@code bulkUpload(file, actingUserId)} overload, so the opening-balance
     * {@code StockMovement} every row with a quantity now writes (BULK_IMPORT_DESIGN.md section 3)
     * is attributed to a real user rather than {@code createdBy = null} - the same
     * {@code @AuthenticationPrincipal} extraction {@link #create} already does for its own ledger
     * write, and for the same reason.
     */
    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<BulkUploadResponse> bulkUpload(
            @RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productManagementService.bulkUpload(file, principal.getUserId()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public Page<ProductResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return productManagementService.list(search, active, pageable);
    }

    /**
     * The fixed unit-of-measure catalog (see {@link UnitOfMeasure}), so the
     * product form's picker doesn't hardcode a second copy of the list. Flat,
     * with each row carrying its category, so the frontend groups client-side.
     *
     * <p>VIEW_PRODUCTS rather than MANAGE_PRODUCTS: reading this list is a
     * prerequisite for even LOOKING at a product's unit, not for changing one,
     * and every role that can see the catalog (company or vendor) needs it -
     * same reasoning as {@link #list}.
     */
    @GetMapping("/units-of-measure")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public List<UnitOfMeasureResponse> unitsOfMeasure() {
        return UnitOfMeasure.all().stream().map(UnitOfMeasureResponse::from).toList();
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public List<ProductResponse> lowStock() {
        return productManagementService.lowStock();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ProductResponse get(@PathVariable UUID id) {
        return productManagementService.get(id);
    }

    /**
     * V19: calls the 3-arg {@code create(request, image, actingUserId)} overload rather than
     * the pre-V19 2-arg one, so {@code request.initialVendor()}'s opening {@code StockMovement}
     * (when present) is attributed to a real user instead of {@code createdBy = null} - the same
     * {@code @AuthenticationPrincipal} extraction {@link StockController} already uses.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productManagementService.create(request, image, principal.getUserId()));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ProductResponse update(
            @PathVariable UUID id,
            @Valid @RequestPart("product") UpdateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return productManagementService.update(id, request, image, principal);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        productManagementService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The other half of {@link #deactivate}, which has had no counterpart since
     * deactivation existed - see {@code ProductManagementService.activate}.
     *
     * <p>A POST to a sub-path rather than a second verb on {@code /{id}}: the DELETE
     * above is already spoken for and the natural mirror, an undelete, has no HTTP
     * method. Rewriting the whole product through the multipart PUT with {@code
     * active: true} is the only alternative and is a far heavier request to make of a
     * caller that wants to flip one flag. Same {@code MANAGE_PRODUCTS} authority as
     * its counterpart - whoever may take a product out of circulation may put it back.
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        productManagementService.activate(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<byte[]> xlsxResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
