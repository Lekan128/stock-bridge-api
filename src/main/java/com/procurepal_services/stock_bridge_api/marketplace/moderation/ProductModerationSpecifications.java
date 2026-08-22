package com.procurepal_services.stock_bridge_api.marketplace.moderation;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Predicates for the super admin's listing-moderation queue.
 *
 * <h2>The seller pin is the whole safety property, exactly as it is on the public catalog</h2>
 * A super admin has no {@code TenantContext} at all ({@code SuperAdminPrincipal} is
 * deliberately not a TenantPrincipal), so the Hibernate tenant filter is off for the
 * whole request and nothing scopes these queries but the predicates built here. That is
 * the same hazard {@code MarketplaceProductSpecifications} manages, arrived at from the
 * other direction, and it bites harder here: {@code approval_status} defaults to PENDING
 * on EVERY products row, so "give me the PENDING products" without a seller pin returns
 * every buying company's private inventory - every restaurant's list of its own
 * cooking oil - and presents it to an operator as work to be approved.
 *
 * <p>The pin is therefore {@code client_id IN (<vendor sellers>)}, and the seller set
 * is supplied by the caller from {@link
 * com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory} minus the
 * platform owner. An empty set yields {@code 1 = 0} - an empty queue - never an
 * unfiltered read.
 */
final class ProductModerationSpecifications {

    private ProductModerationSpecifications() {
    }

    /**
     * The queue. {@code status} null means "every moderated listing whatever its state",
     * which is what the operator's "all" tab shows; the default view passes PENDING.
     */
    static Specification<Product> queue(
            Collection<UUID> vendorSellerIds, ProductApprovalStatus status, UUID sellerId, String query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // First and unconditional - see the class javadoc.
            if (vendorSellerIds == null || vendorSellerIds.isEmpty()) {
                predicates.add(cb.disjunction());
            } else {
                predicates.add(root.get("clientId").in(vendorSellerIds));
            }
            if (sellerId != null) {
                // Narrows the pin, never replaces it: a sellerId naming the platform
                // owner or a buying company intersects to nothing, which is correct.
                predicates.add(cb.equal(root.get("clientId"), sellerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("approvalStatus"), status));
            }
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("sku")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("brand"), "")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * One product, pinned to the vendor-seller set.
     *
     * <p>The moderation equivalent of {@code MarketplaceProductSpecifications.ownedBy}:
     * making every moderation WRITE resolve its target through this predicate is what
     * stops a request naming a buying company's product id from approving, rejecting or
     * disclosing a row nobody is entitled to moderate. A super admin can already see a
     * great deal; that is not the same as being able to stamp an operator's decision
     * onto a tenant's private stock list.
     */
    static Specification<Product> moderatable(Collection<UUID> vendorSellerIds, UUID productId) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (vendorSellerIds == null || vendorSellerIds.isEmpty()) {
                predicates.add(cb.disjunction());
            } else {
                predicates.add(root.get("clientId").in(vendorSellerIds));
            }
            predicates.add(cb.equal(root.get("id"), productId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
