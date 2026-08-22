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
 * Filters for a SELLER's fulfilment queue.
 *
 * <h2>sellerClientId is the isolation, and that is why it is not optional</h2>
 * Note the deliberate absence of a TENANT predicate - the opposite of
 * {@code ProductSpecifications.forTenant}. This queue exists to read EVERY buyer's
 * orders, so it runs inside {@code VendorGuard.readOwnSales(...)}, which lifts the
 * Hibernate filter after proving the caller may sell. A client_id predicate here would
 * only ever be the seller's own id, and would return nothing.
 *
 * <p>With the tenant filter lifted, {@code seller_client_id} is the ONLY thing standing
 * between one vendor and every other vendor's orders. It is therefore the FIRST and a
 * REQUIRED parameter, not one of the optional filters below it - a null seller id
 * cannot silently mean "all sellers" the way a null status means "all statuses". It is
 * rejected outright rather than defaulted, because the safe default does not exist:
 * every possible fallback here is either an empty queue or a data leak.
 *
 * {@code q} searches the order number and the delivery contact/city, plus - via
 * clientIdsMatchingQuery, resolved by the caller - the buyer's company name. The
 * company name lives on {@code clients}, which Order has no association to (client_id
 * is a raw column), so it is resolved to ids first rather than joined.
 */
final class MarketplaceOrderSpecifications {

    private MarketplaceOrderSpecifications() {
    }

    /**
     * @param sellerClientId whose queue this is. Required.
     * @throws IllegalArgumentException if null - see the class javadoc. Failing loudly
     *     at the call site beats returning a Specification that quietly matches every
     *     seller's orders.
     */
    static Specification<Order> forQueue(
            UUID sellerClientId,
            OrderStatus status,
            PaymentStatus paymentStatus,
            UUID clientId,
            String query,
            Collection<UUID> clientIdsMatchingQuery,
            OffsetDateTime from,
            OffsetDateTime to) {
        if (sellerClientId == null) {
            throw new IllegalArgumentException("A fulfilment queue must be scoped to a seller");
        }
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // First, and unconditional.
            predicates.add(cb.equal(root.get("sellerClientId"), sellerClientId));
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
