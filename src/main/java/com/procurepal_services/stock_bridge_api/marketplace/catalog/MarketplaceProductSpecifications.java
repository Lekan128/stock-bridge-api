package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.entity.Product;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Predicates for the marketplace catalog, public and admin.
 *
 * Same rationale as {@link com.procurepal_services.stock_bridge_api.product.ProductSpecifications}
 * for building the client_id predicate by hand - except here it is not belt-and-braces,
 * it is the ONLY thing scoping the query. The public catalog endpoints are in
 * SecurityConfig.PERMIT_ALL_PATHS, so they run with no authenticated principal, no
 * TenantContext, and therefore with the Hibernate tenant filter switched off for the
 * whole request. Nothing else stands between an anonymous GET and every tenant's
 * products. Every public factory below therefore pins three things at once:
 * client_id = the platform owner, is_marketplace_listed = true, is_active = true.
 *
 * The listed/active pair is not made redundant by the client_id pin either: ProcurePal
 * is an ordinary tenant that also runs its own inventory here, so plenty of its rows are
 * private stock that was never put up for sale.
 */
final class MarketplaceProductSpecifications {

    private MarketplaceProductSpecifications() {
    }

    /**
     * The storefront grid. {@code sort} is applied here rather than through the
     * Pageable so RELEVANCE can order on a CASE expression that has no property name.
     */
    static Specification<Product> publicCatalog(
            UUID platformOwnerId,
            String query,
            UUID categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean inStockOnly,
            Collection<UUID> fullyCommittedProductIds,
            CatalogSort sort) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(platformOwnerId, root, cb);

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                // Brand matters as much as name to a buyer typing "Mamador" or
                // "Dangote"; description is in there so a search for "parboiled" finds
                // the rice whose name is only "Long Grain Rice 50kg". COALESCE because
                // both columns are nullable and NULL LIKE '%x%' is NULL, not false -
                // which would be fine here, but not once these move under an OR with
                // NOT in some later filter.
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("sku")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("brand"), "")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("unitPrice"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("unitPrice"), maxPrice));
            }
            if (inStockOnly) {
                // Two predicates, because "in stock" means SELLABLE stock and sellable
                // stock is not a column. quantity_on_hand > 0 is the cheap, indexable
                // half; the exclusion set is the rest - products whose whole shelf is
                // already sold and awaiting dispatch (CatalogStockService's
                // FULLY_COMMITTED_SQL).
                //
                // Filtering here rather than dropping rows from the returned page is what
                // keeps totalElements honest: a post-filter would make page 3 of a
                // filtered grid arrive short with no explanation.
                predicates.add(cb.greaterThan(root.get("quantityOnHand"), 0));
                if (fullyCommittedProductIds != null && !fullyCommittedProductIds.isEmpty()) {
                    predicates.add(cb.not(root.get("id").in(fullyCommittedProductIds)));
                }
            }

            applyOrder(root, criteriaQuery, cb, sort, fullyCommittedProductIds);
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The batch cart-hydration form, {@code ?ids=a,b,c}. Sharing the listed/active/owner
     * predicate with the grid is the point: a cart line for a product ProcurePal has
     * since unlisted must fall out of the cart, not resurrect through a side door.
     */
    static Specification<Product> publicCatalogByIds(UUID platformOwnerId, Collection<UUID> ids) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(platformOwnerId, root, cb);
            predicates.add(root.get("id").in(ids));
            applyOrder(root, criteriaQuery, cb, CatalogSort.NAME_ASC, Set.of());
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** "More from this category", minus the product being viewed. */
    static Specification<Product> related(
            UUID platformOwnerId, UUID categoryId, UUID excludeProductId, Collection<UUID> fullyCommittedProductIds) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(platformOwnerId, root, cb);
            predicates.add(cb.notEqual(root.get("id"), excludeProductId));
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            applyOrder(root, criteriaQuery, cb, CatalogSort.RELEVANCE, fullyCommittedProductIds);
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * ProcurePal's own catalog admin list. No listed/active predicate by default -
     * the whole job of that screen is to find the products that are NOT yet listed -
     * but client_id is still pinned to the platform owner, so this endpoint cannot
     * become a cross-tenant product browser even if the guard above it were removed.
     *
     * Ordering is left to the Pageable here: the admin list has no relevance concept,
     * so a plain Sort covers it and keeps the count query untouched.
     */
    static Specification<Product> adminCatalog(UUID platformOwnerId, String query, UUID categoryId, Boolean listed) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), platformOwnerId));

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("sku")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("brand"), "")), pattern)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (listed != null) {
                predicates.add(cb.equal(root.get("marketplaceListed"), listed));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * One product, pinned to the platform owner.
     *
     * This is the join a CHECK constraint cannot do (contract §4.5): the rule
     * "is_marketplace_listed is only ever true on the platform owner's products" needs
     * clients.is_platform_owner, which is on another table, so the database cannot
     * enforce it. Making every admin write resolve its target through this predicate is
     * the enforcement - a request naming another tenant's product id 404s instead of
     * listing someone else's stock on ProcurePal's storefront.
     */
    static Specification<Product> ownedBy(UUID platformOwnerId, UUID productId) {
        return (root, criteriaQuery, cb) ->
                cb.and(cb.equal(root.get("clientId"), platformOwnerId), cb.equal(root.get("id"), productId));
    }

    /** Every product in a category, across ALL tenants - see MarketplaceCatalogAdminService.deleteCategory. */
    static Specification<Product> inCategory(UUID categoryId) {
        return (root, criteriaQuery, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /** The three predicates that together make a row public. Never use one without the other two. */
    private static List<Predicate> listedBy(UUID platformOwnerId, Root<Product> root, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("clientId"), platformOwnerId));
        predicates.add(cb.isTrue(root.get("marketplaceListed")));
        predicates.add(cb.isTrue(root.get("active")));
        return predicates;
    }

    /**
     * Ordering lives in the Specification because RELEVANCE sorts on a CASE
     * expression, which {@code Sort.by(...)} cannot name. Callers therefore pass an
     * UNSORTED Pageable - a sorted one would make Spring Data call {@code orderBy}
     * again afterwards and silently replace everything set here.
     *
     * The result-type guard is not optional: Spring Data reuses the same Specification
     * to build the {@code count(*)} query behind the page total, and an ORDER BY over
     * a non-grouped column in that query is a SQL error.
     */
    private static void applyOrder(Root<Product> root, CriteriaQuery<?> criteriaQuery, CriteriaBuilder cb,
            CatalogSort sort, Collection<UUID> fullyCommittedProductIds) {
        if (criteriaQuery == null) {
            return;
        }
        Class<?> resultType = criteriaQuery.getResultType();
        if (resultType == Long.class || resultType == long.class) {
            return;
        }

        Order byName = cb.asc(cb.lower(root.get("name")));
        List<Order> orders =
                switch (sort) {
                    case PRICE_ASC -> List.of(cb.asc(root.get("unitPrice")), byName);
                    case PRICE_DESC -> List.of(cb.desc(root.get("unitPrice")), byName);
                    case NAME_ASC -> List.of(byName);
                    case NEWEST -> List.of(cb.desc(root.get("createdAt")), byName);
                    case RELEVANCE -> List.of(cb.desc(sellableFirst(root, cb, fullyCommittedProductIds)), byName);
                };
        criteriaQuery.orderBy(orders);
    }

    /**
     * 1 for anything a buyer can actually add to a cart today, 0 for the rest.
     *
     * "Actually" is doing work here: it has to agree with the {@code inStock} flag the DTO
     * publishes, which is SELLABLE stock, not the raw column. Ordering on
     * quantity_on_hand alone would float a product to the top of the grid and then render
     * it with an out-of-stock badge, which is the exact inconsistency this ordering exists
     * to avoid.
     */
    private static Expression<Integer> sellableFirst(
            Root<Product> root, CriteriaBuilder cb, Collection<UUID> fullyCommittedProductIds) {
        Predicate hasStock = cb.greaterThan(root.get("quantityOnHand"), 0);
        Predicate sellable = fullyCommittedProductIds == null || fullyCommittedProductIds.isEmpty()
                ? hasStock
                : cb.and(hasStock, cb.not(root.get("id").in(fullyCommittedProductIds)));
        return cb.<Integer>selectCase().when(sellable, 1).otherwise(0);
    }
}
