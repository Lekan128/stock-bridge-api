package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductExcelService;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ProductExcelService productExcelService;

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<byte[]> template() {
        return xlsxResponse(productExcelService.generateTemplate(), "product-import-template.xlsx");
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ResponseEntity<byte[]> export() {
        return xlsxResponse(productManagementService.exportActiveProducts(), "products-export.xlsx");
    }

    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<BulkUploadResponse> bulkUpload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productManagementService.bulkUpload(file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public Page<ProductResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return productManagementService.list(search, active, pageable);
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productManagementService.create(request, image));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ProductResponse update(
            @PathVariable UUID id,
            @Valid @RequestPart("product") UpdateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return productManagementService.update(id, request, image);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        productManagementService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<byte[]> xlsxResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
