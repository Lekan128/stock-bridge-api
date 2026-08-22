package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
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
 * products.
 *
 * <h2>The seller pin widened from one id to a set, and did not go away</h2>
 * Before vendors, the pin was {@code client_id = <the platform owner>}. Now that any
 * approved vendor can list, it is {@code client_id IN (<the active sellers>)} - see
 * {@link com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory}, which
 * is the one place that set is computed. The distinction that matters: this is still
 * an ALLOWLIST of ids, not the absence of a filter. Every tenant on the platform has
 * a products table full of their own private stock, and dropping the predicate -
 * rather than widening it - would publish all of it to anonymous visitors. If the
 * seller set is ever empty, the correct behaviour is an empty catalog; callers must
 * never fall through to an unpinned query.
 *
 * <h2>Four predicates now, not three</h2>
 * A row is public only if ALL of these hold: its client_id is an active seller's,
 * is_marketplace_listed, is_active, AND approval_status = APPROVED.
 *
 * None is redundant with the others. The listed/active pair is not implied by the
 * seller pin - ProcurePal and every vendor are ordinary tenants that also run their
 * own inventory here, so plenty of their rows are private stock that was never put up
 * for sale. And approval is not implied by listing: a vendor may flip
 * is_marketplace_listed on their own product at any time, which is precisely the
 * action moderation exists to gate. Listing is the seller saying "I want this sold";
 * approval is the platform saying "yes". Both are required, and only one of them is
 * the seller's to give.
 */
final class MarketplaceProductSpecifications {

    private MarketplaceProductSpecifications() {
    }

    /**
     * The storefront grid. {@code sort} is applied here rather than through the
     * Pageable so RELEVANCE can order on a CASE expression that has no property name.
     */
    static Specification<Product> publicCatalog(
            Collection<UUID> sellerIds,
            UUID sellerId,
            String query,
            UUID categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean inStockOnly,
            Collection<UUID> fullyCommittedProductIds,
            CatalogSort sort) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(sellerIds, root, cb);

            if (sellerId != null) {
                // "Show me only this seller's products" - the storefront filter and
                // the per-vendor storefront page. NARROWS the allowlist above rather
                // than replacing it: an id naming an inactive vendor, a buying
                // company, or a client that does not exist intersects the pin to
                // nothing and yields an empty grid, which is the correct answer and
                // is reached without a second lookup.
                predicates.add(cb.equal(root.get("clientId"), sellerId));
            }
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
    static Specification<Product> publicCatalogByIds(Collection<UUID> sellerIds, Collection<UUID> ids) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(sellerIds, root, cb);
            predicates.add(root.get("id").in(ids));
            applyOrder(root, criteriaQuery, cb, CatalogSort.NAME_ASC, Set.of());
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * One product by slug, across every active seller.
     *
     * <p>Note that {@code products.slug} is unique PER TENANT, not globally - V6's
     * partial unique index is on {@code (client_id, slug)}. Two sellers may therefore
     * hold the same slug, and this returns whichever the ordering puts first. The
     * caller is a detail page reached from a link, and the id form is the unambiguous
     * one; see MarketplaceCatalogService.findListedOrThrow for why this is flagged
     * rather than fixed here.
     */
    static Specification<Product> publicCatalogBySlug(Collection<UUID> sellerIds, String slug) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(sellerIds, root, cb);
            predicates.add(cb.equal(root.get("slug"), slug));
            applyOrder(root, criteriaQuery, cb, CatalogSort.NAME_ASC, Set.of());
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * "More from this category", minus the product being viewed.
     *
     * Deliberately spans ALL active sellers rather than staying inside the viewed
     * product's own seller. A buyer on a rice detail page wants more rice, not more
     * of that vendor - and confining the rail to one seller would quietly turn a
     * merchandising surface into an advertisement for whoever happened to be
     * clicked first. The per-vendor storefront page is where "more from this
     * seller" belongs, and it exists.
     */
    static Specification<Product> related(
            Collection<UUID> sellerIds,
            UUID categoryId,
            UUID excludeProductId,
            Collection<UUID> fullyCommittedProductIds) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = listedBy(sellerIds, root, cb);
            predicates.add(cb.notEqual(root.get("id"), excludeProductId));
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            applyOrder(root, criteriaQuery, cb, CatalogSort.RELEVANCE, fullyCommittedProductIds);
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * A SELLER's own catalog admin list - ProcurePal's, or a vendor's. No listed/active
     * predicate by default: the whole job of that screen is to find the products that
     * are NOT yet listed.
     *
     * <h2>The owner id is a parameter, and that is the whole isolation story</h2>
     * It used to be the platform owner's, always, because ProcurePal was the only
     * seller. It is now whichever seller is asking - {@code MarketplaceCatalogAdminController}
     * passes the platform owner's after {@code requirePlatformOwner()},
     * {@code VendorCatalogueController} passes the caller's own after
     * {@code requireSeller()} - and in both cases it comes from a guard, never from a
     * request parameter. The predicate itself did not change and does not need to: it
     * pins client_id to exactly one id, so this endpoint cannot become a cross-tenant
     * product browser whichever guard sits above it.
     *
     * Ordering is left to the Pageable here: the admin list has no relevance concept,
     * so a plain Sort covers it and keeps the count query untouched.
     */
    static Specification<Product> adminCatalog(UUID ownerId, String query, UUID categoryId, Boolean listed) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), ownerId));

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
     * One product, pinned to the seller who is asking.
     *
     * This is the join a CHECK constraint cannot do (contract §4.5). The rule it enforces
     * has widened with the marketplace - it was "is_marketplace_listed is only ever true
     * on the platform owner's products", and it is now "a seller may only list products
     * they own" - but the mechanism is unchanged and is the reason the widening was safe.
     * Ownership lives in {@code products.client_id} and sellerhood in
     * {@code clients.client_type}, both on other tables from each other, so the database
     * cannot enforce either. Making every admin write resolve its target through this
     * predicate is the enforcement: a request naming another company's product id 404s
     * instead of putting somebody else's stock on the storefront under the caller's name.
     *
     * <p>{@code ownerId} always comes from a guard - {@code requirePlatformOwner()} or
     * {@code requireSeller()} - and never from a request. That is what makes "pinned to
     * the caller" true rather than merely intended.
     */
    static Specification<Product> ownedBy(UUID ownerId, UUID productId) {
        return (root, criteriaQuery, cb) ->
                cb.and(cb.equal(root.get("clientId"), ownerId), cb.equal(root.get("id"), productId));
    }

    /** Every product in a category, across ALL tenants - see MarketplaceCatalogAdminService.deleteCategory. */
    static Specification<Product> inCategory(UUID categoryId) {
        return (root, criteriaQuery, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /**
     * The four predicates that together make a row public. Never use one without the
     * other three - see the class javadoc for why none is implied by the rest.
     *
     * <p>An EMPTY seller set produces {@code 1 = 0}, not "no seller predicate". That
     * is the whole safety property of this method: an unseeded marketplace, or one
     * whose only vendor was just suspended, must answer with an empty catalog rather
     * than with every tenant's private inventory. Written explicitly because
     * {@code in()} on an empty collection is a well-known way to generate either a
     * SQL syntax error or a silently true predicate depending on the provider, and
     * neither is a failure this endpoint may have.
     */
    private static List<Predicate> listedBy(Collection<UUID> sellerIds, Root<Product> root, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (sellerIds == null || sellerIds.isEmpty()) {
            predicates.add(cb.disjunction());
        } else {
            predicates.add(root.get("clientId").in(sellerIds));
        }
        predicates.add(cb.isTrue(root.get("marketplaceListed")));
        predicates.add(cb.isTrue(root.get("active")));
        // Moderation. A vendor controls is_marketplace_listed on their own rows; only
        // a super admin controls this one, which is what makes the pair a gate rather
        // than a formality. ProcurePal's products are stamped APPROVED at write time
        // (ProductModerationService) so the operator never queues behind itself.
        predicates.add(cb.equal(root.get("approvalStatus"), ProductApprovalStatus.APPROVED));
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
