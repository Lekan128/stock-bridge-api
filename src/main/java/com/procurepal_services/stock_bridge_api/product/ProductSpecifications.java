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
        return forTenant(clientId, search, active, categoryId, stockStatus, null);
    }

    /**
     * @param hasSellingPrice true keeps products with a selling price, false those without, null
     *     both - the Inventory list's "Has a price · No selling price" census
     *     (SELLING_PRICE_PLAN.md section 6.5).
     */
    static Specification<Product> forTenant(
            UUID clientId, String search, Boolean active, UUID categoryId, StockStatus stockStatus, Boolean hasSellingPrice) {
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

            if (hasSellingPrice != null) {
                predicates.add(hasSellingPrice ? cb.isNotNull(root.get("unitPrice")) : cb.isNull(root.get("unitPrice")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * At one branch (MULTI_BRANCH_BUILD_PLAN.md section 6, Phase 4): the stock filter reads that
     * branch's figure against its own level, else the product's; a branch that holds none reads 0.
     * "All branches" is not here: OUT there is the company figure ({@link #forTenant}), and LOW / OK
     * are {@code EveryBranchStockQuery}'s, which needs one pass over every branch's figures.
     */
    static Specification<Product> atBranch(
            UUID clientId, String search, Boolean active, UUID categoryId, StockStatus stockStatus, Boolean hasSellingPrice,
            UUID branchId) {
        Specification<Product> base = forTenant(clientId, search, active, categoryId, null, hasSellingPrice);
        if (stockStatus == null) {
            return base;
        }
        return base.and((root, query, cb) -> {
            jakarta.persistence.criteria.Subquery<Integer> quantity = query.subquery(Integer.class);
            Root<com.procurepal_services.stock_bridge_api.entity.BranchStockEntry> held =
                    quantity.from(com.procurepal_services.stock_bridge_api.entity.BranchStockEntry.class);
            quantity.select(held.get("quantityOnHand"))
                    .where(cb.equal(held.get("productId"), root.get("id")), cb.equal(held.get("branchId"), branchId));
            jakarta.persistence.criteria.Subquery<Integer> level = query.subquery(Integer.class);
            Root<com.procurepal_services.stock_bridge_api.entity.BranchStockEntry> own =
                    level.from(com.procurepal_services.stock_bridge_api.entity.BranchStockEntry.class);
            level.select(own.get("lowStockThreshold"))
                    .where(cb.equal(own.get("productId"), root.get("id")), cb.equal(own.get("branchId"), branchId));
            jakarta.persistence.criteria.Expression<Integer> figure = cb.coalesce(quantity, 0);
            jakarta.persistence.criteria.Expression<Integer> threshold = cb.coalesce(level, root.<Integer>get("lowStockThreshold"));
            Predicate out = cb.le(figure, 0);
            Predicate atOrBelow = cb.and(cb.isNotNull(threshold), cb.le(figure, threshold));
            return switch (stockStatus) {
                case OUT -> out;
                case LOW -> cb.and(cb.not(out), atOrBelow);
                case OK -> cb.not(cb.or(out, atOrBelow));
            };
        });
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
