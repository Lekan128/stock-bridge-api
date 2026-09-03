package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Builds the tenant-scoping predicate explicitly - see ProductSpecifications for the same rationale. */
final class StockMovementSpecifications {

    private StockMovementSpecifications() {
    }

    static Specification<StockMovement> forTenant(
            UUID clientId, UUID productId, OffsetDateTime from, OffsetDateTime to, MovementType movementType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));

            if (productId != null) {
                predicates.add(cb.equal(root.get("product").get("id"), productId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (movementType != null) {
                predicates.add(cb.equal(root.get("movementType"), movementType));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Every {@code IN} movement (lot) of one product, with its supplier already joined - the row
     * set behind {@code GET /api/products/{id}/lots} (UNIT_UX_CONTRACT.md section 4). Sorting is
     * the caller's, since the lot list and a future lot report want different orders while the
     * predicate is the same.
     *
     * <h2>The LEFT JOIN FETCH is the point of having a second specification</h2>
     * Every row of the response names its supplier, and {@code StockMovement.companyVendor} is
     * LAZY - so without this the endpoint issues one extra query per lot, on the one screen
     * whose whole job is to list every lot a product has. LEFT rather than inner because a lot
     * recorded before any supplier was on file legitimately has none, and an inner join would
     * quietly drop exactly the oldest stock, which FIFO draws from first.
     *
     * <p>The result-type guard is not optional: Spring Data reuses a specification for its own
     * {@code COUNT} query when one is needed, and a fetch join in a count query is invalid JPQL.
     * {@link #forTenant} above needs no such guard because it fetches nothing.
     */
    static Specification<StockMovement> lotsForProduct(UUID clientId, UUID productId) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("companyVendor", JoinType.LEFT);
            }
            return cb.and(
                    cb.equal(root.get("clientId"), clientId),
                    cb.equal(root.get("product").get("id"), productId),
                    cb.equal(root.get("movementType"), MovementType.IN));
        };
    }
}
