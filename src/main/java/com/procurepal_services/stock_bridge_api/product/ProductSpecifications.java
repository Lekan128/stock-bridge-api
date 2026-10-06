package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
        return forTenant(clientId, search, active, null, null);
    }

    /**
     * @param categoryId the company category to narrow to, or null for all. The category itself is
     *     fetched with each page of products, so naming it on every row costs no extra query.
     */
    static Specification<Product> forTenant(UUID clientId, String search, Boolean active, UUID categoryId) {
        return forTenant(clientId, search, active, categoryId, null);
    }

    /**
     * @param stockStatus narrows to one of {@link StockStatus}'s three values, or null for every
     *     product regardless of stock level. Independent of {@code active} — a caller viewing
     *     inactive products can still ask which of them are out of stock.
     */
    static Specification<Product> forTenant(
            UUID clientId, String search, Boolean active, UUID categoryId, StockStatus stockStatus) {
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

            if (stockStatus != null) {
                predicates.add(stockStatusPredicate(stockStatus, root, cb));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * {@code OUT} wins over {@code LOW} where both could apply (zero on hand with a threshold
     * set) — see {@link StockStatus}'s own javadoc for why. Kept here rather than inlined above so
     * the three branches read as one definition next to each other instead of split across two
     * `if`s each handling an overlapping case.
     */
    private static Predicate stockStatusPredicate(StockStatus stockStatus, Root<Product> root, CriteriaBuilder cb) {
        Predicate outOfStock = cb.le(root.get("quantityOnHand"), 0);
        Predicate hasThreshold = cb.isNotNull(root.get("lowStockThreshold"));
        Predicate atOrBelowThreshold = cb.le(root.get("quantityOnHand"), root.get("lowStockThreshold"));
        Predicate lowStock = cb.and(cb.not(outOfStock), hasThreshold, atOrBelowThreshold);

        return switch (stockStatus) {
            case OUT -> outOfStock;
            case LOW -> lowStock;
            case OK -> cb.not(cb.or(outOfStock, cb.and(hasThreshold, atOrBelowThreshold)));
        };
    }
}
