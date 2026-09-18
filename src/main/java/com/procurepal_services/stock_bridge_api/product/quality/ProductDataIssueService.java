package com.procurepal_services.stock_bridge_api.product.quality;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.product.SkuTakenException;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.quality.dto.ProductDataIssueResponse;
import com.procurepal_services.stock_bridge_api.product.quality.dto.ProductDataIssueResponse.Issue;
import com.procurepal_services.stock_bridge_api.product.sku.ProductSkuSettingsService;
import com.procurepal_services.stock_bridge_api.product.sku.SkuGenerationService;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one-time tidy-up (BULK_IMPORT_CX_PLAN.md task 1.8): products whose code an older spreadsheet
 * reader damaged, products saved before units existed, and setups that are legal but almost
 * certainly slips. Listed so the owner can fix each once; the list empties as they do.
 */
@Service
@RequiredArgsConstructor
public class ProductDataIssueService {

    private final ProductRepository productRepository;
    private final ProductSkuSettingsService productSkuSettingsService;
    private final SkuGenerationService skuGenerationService;

    @Transactional(readOnly = true)
    public List<ProductDataIssueResponse> list() {
        UUID tenantId = requireTenantId();
        boolean generated = productSkuSettingsService.isEnabled(tenantId);
        List<ProductDataIssueResponse> result = new ArrayList<>();
        for (Product product : productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(tenantId)) {
            List<Issue> issues = issuesOf(tenantId, product, generated);
            if (!issues.isEmpty()) {
                result.add(new ProductDataIssueResponse(
                        product.getId(), product.getName(), product.getSku(), product.getQuantityOnHand(), issues));
            }
        }
        return result;
    }

    /**
     * Replaces a damaged code. Only a damaged one: changing a working code is an ordinary edit,
     * with its own permission rules, on the product page.
     *
     * @param code the new code; blank uses the suggestion, or a generated code when the company
     *     generates them.
     */
    @Transactional
    public ProductResponse fixCode(UUID productId, String code) {
        UUID tenantId = requireTenantId();
        Product product = productRepository.findByIdForCurrentTenant(productId).orElseThrow(ProductNotFoundException::new);
        if (!ProductSetupChecks.isDamagedSku(product.getSku())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This product's code isn't damaged. Change it on the product page instead.");
        }
        String next;
        if (code != null && !code.isBlank()) {
            next = code.trim();
        } else if (productSkuSettingsService.isEnabled(tenantId)) {
            next = skuGenerationService.generateAndReserveOne(tenantId, product.getName());
        } else {
            next = suggestedCode(tenantId, product);
        }
        productRepository.findByClientIdAndSku(tenantId, next).ifPresent(other -> {
            if (!other.getId().equals(productId)) {
                throw new SkuTakenException(next);
            }
        });
        product.setSku(next);
        productRepository.saveAndFlush(product);
        return ProductResponse.from(product);
    }

    private List<Issue> issuesOf(UUID tenantId, Product product, boolean generated) {
        List<Issue> issues = new ArrayList<>();
        if (ProductSetupChecks.isDamagedSku(product.getSku())) {
            issues.add(new Issue("DAMAGED_CODE",
                    "The code %s looks damaged - an older spreadsheet read it as a number. It was probably %s."
                            .formatted(product.getSku(), undamaged(product.getSku())),
                    generated ? null : suggestedCode(tenantId, product)));
        }
        if (product.getUnitOfMeasure() == null || product.getUnitOfMeasure().isBlank()) {
            issues.add(new Issue("NO_STOCK_UNIT",
                    "%s has no unit, so the %d in stock are just \"%d\". Choose what it is counted in - kg, litres, pieces."
                            .formatted(product.getName(), product.getQuantityOnHand(), product.getQuantityOnHand()),
                    null));
        }
        ProductSetupChecks.lengthUnitWarning(product.getName(), product.getUnitOfMeasure())
                .ifPresent(message -> issues.add(new Issue("UNIT_LOOKS_WRONG", message, null)));
        ProductSetupChecks.packSizeWarning(product.getName(), product.getUnitOfMeasure(),
                        product.getPackagingUnit(), product.getPackagingSize())
                .ifPresent(message -> issues.add(new Issue("PACK_LOOKS_TOO_SMALL", message, null)));
        return issues;
    }

    /** "28.0" was 28 - unless 28 is taken, in which case a code from the name. */
    private String suggestedCode(UUID tenantId, Product product) {
        String undamaged = undamaged(product.getSku());
        if (productRepository.findByClientIdAndSku(tenantId, undamaged).isEmpty()) {
            return undamaged;
        }
        return ProductSetupChecks.codeFromName(product.getName(),
                candidate -> productRepository.findByClientIdAndSku(tenantId, candidate).isPresent());
    }

    private static String undamaged(String sku) {
        return sku.trim().replaceFirst("\\.0+$", "");
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
