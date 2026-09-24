package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the tenant-scoping predicate explicitly (rather than relying solely
 * on TenantAwareEntity's Hibernate filter) so isolation holds by
 * construction here too - same rationale as TenantScopedRepository.
 */
final class ProductSpecifications {

    private ProductSpecifications() {
    }

    static Specification<Product> forTenant(UUID clientId, String search, Boolean active) {
        return forTenant(clientId, search, active, null);
    }

    /**
     * @param categoryId the company category to narrow to, or null for all. The category itself is
     *     fetched with each page of products, so naming it on every row costs no extra query.
     */
    static Specification<Product> forTenant(UUID clientId, String search, Boolean active, UUID categoryId) {
        return (root, query, cb) -> {
            if (query != null && Product.class.equals(query.getResultType())) {
                root.fetch("companyCategory", jakarta.persistence.criteria.JoinType.LEFT);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("sku")), pattern)));
            }

            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("companyCategory").get("id"), categoryId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
