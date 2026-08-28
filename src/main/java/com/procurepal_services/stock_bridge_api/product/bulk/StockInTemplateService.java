package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.imports.io.ImportLimits;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "stock in these products" into the pre-filled spreadsheet, resolving the tenant data
 * {@link StockInExcelService} deliberately knows nothing about.
 *
 * <h2>Why the filter resolution lives here and not in the controller</h2>
 * The three entry points of BULK_IMPORT_DESIGN.md section 8.1 - select rows on the product list,
 * "Bulk stock in" from the actions menu, and the import page's filter - all produce the same file,
 * and the only difference between them is which products end up in it. Keeping that one decision
 * in one place is what makes them genuinely the same feature rather than three that drift. The
 * controller (M4) supplies the raw query parameters and gets bytes back.
 *
 * <h2>One catalog read, then everything else in memory</h2>
 * Every filter starts from the tenant's active products - one query - and narrows from there,
 * rather than each having its own repository method. At the catalog sizes this feature targets
 * (the sheet is capped at {@link ImportLimits#MAX_ROWS} rows by definition) that is a fraction of
 * a millisecond, and the alternative is four more finders on a repository M1 owns, for filters
 * that are one predicate each.
 */
@Service
@RequiredArgsConstructor
public class StockInTemplateService {

    private final ProductRepository productRepository;
    private final ProductVendorRepository productVendorRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final StockInExcelService stockInExcelService;

    /**
     * @param productIds an explicit selection, which wins over {@code filter} when non-empty -
     *     BULK_IMPORT_CONTRACT.md section 3. It comes from the user having ticked rows on the
     *     product list, and a deliberate act must not be overridden by a default.
     * @param filter used when there is no explicit selection. Null is treated as
     *     {@link StockInTemplateFilter#ALL}.
     * @param vendorId required by {@link StockInTemplateFilter#BY_VENDOR}, ignored otherwise.
     * @param categoryId required by {@link StockInTemplateFilter#BY_CATEGORY}, ignored otherwise.
     */
    @Transactional(readOnly = true)
    public byte[] generate(
            List<UUID> productIds, StockInTemplateFilter filter, UUID vendorId, UUID categoryId) {
        UUID tenantId = requireTenantId();
        List<Product> products = selectProducts(tenantId, productIds, filter, vendorId, categoryId);
        return stockInExcelService.generateTemplate(
                templateRows(tenantId, products), vendorDropdownNames(tenantId), LocalDate.now());
    }

    private List<Product> selectProducts(
            UUID tenantId, List<UUID> productIds, StockInTemplateFilter filter, UUID vendorId, UUID categoryId) {
        if (productIds != null && !productIds.isEmpty()) {
            // Scoped through the tenant's own catalog rather than looked up by id directly, so an
            // id belonging to another tenant simply does not appear in the file - a missing row,
            // never an error naming an id that would confirm the row exists somewhere.
            Set<UUID> wanted = new LinkedHashSet<>(productIds);
            return activeProducts(tenantId).stream()
                    .filter(product -> wanted.contains(product.getId()))
                    .toList();
        }
        StockInTemplateFilter effective = filter == null ? StockInTemplateFilter.ALL : filter;
        return switch (effective) {
            case ALL -> activeProducts(tenantId);
            case LOW_STOCK -> productRepository.findLowStockByClientId(tenantId);
            case BY_VENDOR -> productsOfVendor(tenantId, vendorId);
            case BY_CATEGORY -> activeProducts(tenantId).stream()
                    .filter(product -> product.getCategory() != null
                            && product.getCategory().getId().equals(categoryId))
                    .toList();
        };
    }

    private List<Product> activeProducts(UUID tenantId) {
        return productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(tenantId);
    }

    /**
     * "What do we buy from this supplier", straight off the join - the same read the vendor detail
     * screen uses. A null vendorId yields nothing rather than everything: a BY_VENDOR request with
     * no vendor is a malformed request, and answering it with the whole catalog would silently give
     * the user a very different file from the one they asked for.
     */
    private List<Product> productsOfVendor(UUID tenantId, UUID vendorId) {
        if (vendorId == null) {
            return List.of();
        }
        return productVendorRepository
                .findAllByClientIdAndCompanyVendorIdAndProductActive(tenantId, vendorId)
                .stream()
                .map(ProductVendor::getProduct)
                .toList();
    }

    /**
     * The pre-fill, assembled per section 8.1's column table: supplier and cost from the product's
     * PREFERRED vendor line, unit from its packaging unit falling back to its base unit, packaging
     * size from the vendor's default falling back to the product's own.
     *
     * <p>Every fallback exists because the alternative is a blank cell the user has to fill, and the
     * whole argument of section 5.3 is that they should have to fill exactly one. A product with no
     * vendor line yet still gets its unit and its pack size; only the supplier and the cost are
     * genuinely unknown, and those are the two the review screen is good at asking about.
     */
    private List<StockInTemplateRow> templateRows(UUID tenantId, List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProductVendor> preferredByProductId = new HashMap<>();
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        for (ProductVendor line : productVendorRepository.findPreferredByClientIdAndProductIdIn(tenantId, productIds)) {
            preferredByProductId.put(line.getProduct().getId(), line);
        }

        return products.stream()
                .map(product -> {
                    ProductVendor preferred = preferredByProductId.get(product.getId());
                    return new StockInTemplateRow(
                            product.getId(),
                            product.getSku(),
                            product.getName(),
                            preferred == null ? null : preferred.getCompanyVendor().getName(),
                            product.getPackagingUnit() != null
                                    ? product.getPackagingUnit()
                                    : product.getUnitOfMeasure(),
                            preferred == null ? product.getCostPrice() : preferred.getLastCostPrice(),
                            preferred != null && preferred.getDefaultPackagingSize() != null
                                    ? preferred.getDefaultPackagingSize()
                                    : product.getPackagingSize());
                })
                .toList();
    }

    /** The tenant's suppliers for the dropdown, subject to the same cap the product template uses. */
    private List<String> vendorDropdownNames(UUID tenantId) {
        List<String> names = companyVendorRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(tenantId).stream()
                .map(CompanyVendor::getName)
                .toList();
        return names.size() > ImportLimits.VENDOR_DROPDOWN_CAP ? List.of() : names;
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
