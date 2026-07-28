package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for ProcurePal's fulfilment queue.
 *
 * Note the deliberate absence of a tenant predicate - the opposite of
 * {@code ProductSpecifications.forTenant}. This queue exists to read EVERY buyer's
 * orders, so it must run inside {@code PlatformOwnerGuard.readAcrossTenants(...)},
 * which proves platform ownership before it lifts the Hibernate filter. A client_id
 * predicate here would only ever be the operator's own id, and would return nothing.
 *
 * {@code q} searches the order number and the delivery contact/city, plus - via
 * clientIdsMatchingQuery, resolved by the caller - the buyer's company name. The
 * company name lives on {@code clients}, which Order has no association to (client_id
 * is a raw column), so it is resolved to ids first rather than joined.
 */
final class MarketplaceOrderSpecifications {

    private MarketplaceOrderSpecifications() {
    }

    static Specification<Order> forQueue(
            OrderStatus status,
            PaymentStatus paymentStatus,
            UUID clientId,
            String query,
            Collection<UUID> clientIdsMatchingQuery,
            OffsetDateTime from,
            OffsetDateTime to) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (paymentStatus != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }
            if (clientId != null) {
                predicates.add(cb.equal(root.get("clientId"), clientId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                List<Predicate> textMatches = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("orderNumber")), pattern),
                        cb.like(cb.lower(root.get("deliveryContactName")), pattern),
                        cb.like(cb.lower(root.get("deliveryCity")), pattern)));
                if (clientIdsMatchingQuery != null && !clientIdsMatchingQuery.isEmpty()) {
                    textMatches.add(root.get("clientId").in(clientIdsMatchingQuery));
                }
                predicates.add(cb.or(textMatches.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
