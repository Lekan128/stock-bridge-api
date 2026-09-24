package com.procurepal_services.stock_bridge_api.product.category;

import com.procurepal_services.stock_bridge_api.entity.CompanyCategory;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryResponse;
import com.procurepal_services.stock_bridge_api.repository.CompanyCategoryRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A company's own product categories (BULK_IMPORT_CX_PLAN.md task 1.6). Deliberately small: a
 * name, unique per company whatever its capitalisation. Deleting one leaves its products
 * uncategorised.
 */
@Service
@RequiredArgsConstructor
public class CompanyCategoryService {

    private final CompanyCategoryRepository companyCategoryRepository;

    @Transactional(readOnly = true)
    public List<CompanyCategoryResponse> list() {
        UUID tenantId = requireTenantId();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : companyCategoryRepository.countActiveProductsByCategory(tenantId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return companyCategoryRepository.findAllByClientIdOrderByNameAsc(tenantId).stream()
                .map(category -> new CompanyCategoryResponse(
                        category.getId(), category.getName(), counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CompanyCategoryResponse create(String name) {
        UUID tenantId = requireTenantId();
        String clean = clean(name);
        if (companyCategoryRepository.findByClientIdAndNameIgnoreCase(tenantId, clean).isPresent()) {
            throw CompanyCategoryException.duplicate(clean);
        }
        CompanyCategory saved = companyCategoryRepository.saveAndFlush(CompanyCategory.builder().name(clean).build());
        return new CompanyCategoryResponse(saved.getId(), saved.getName(), 0);
    }

    @Transactional
    public CompanyCategoryResponse rename(UUID id, String name) {
        UUID tenantId = requireTenantId();
        CompanyCategory category = require(id);
        String clean = clean(name);
        Optional<CompanyCategory> clash = companyCategoryRepository.findByClientIdAndNameIgnoreCase(tenantId, clean);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw CompanyCategoryException.duplicate(clean);
        }
        category.setName(clean);
        companyCategoryRepository.saveAndFlush(category);
        return list().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(UUID id) {
        companyCategoryRepository.delete(require(id));
    }

    /** The company's category with this id - for product create and update. */
    @Transactional(readOnly = true)
    public CompanyCategory require(UUID id) {
        return companyCategoryRepository.findByIdAndClientId(id, requireTenantId())
                .orElseThrow(CompanyCategoryException::notFound);
    }

    /**
     * The category with this name, created if the company has none yet - how a spreadsheet's
     * Category column is applied. Matching ignores capitalisation and surrounding spaces.
     */
    @Transactional
    public CompanyCategory findOrCreate(UUID tenantId, String name) {
        String clean = clean(name);
        return companyCategoryRepository.findByClientIdAndNameIgnoreCase(tenantId, clean)
                .orElseGet(() -> companyCategoryRepository.saveAndFlush(CompanyCategory.builder().name(clean).build()));
    }

    /** Trimmed, inner runs of spaces collapsed, and within the length limit. */
    public static String clean(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw new CompanyCategoryException(org.springframework.http.HttpStatus.BAD_REQUEST, "A category needs a name.");
        }
        if (clean.length() > CompanyCategory.NAME_MAX_LENGTH) {
            throw new CompanyCategoryException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Category names can be at most " + CompanyCategory.NAME_MAX_LENGTH + " characters.");
        }
        return clean;
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
