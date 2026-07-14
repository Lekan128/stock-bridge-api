package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
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
}
