package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpectedDeliveryRepository extends JpaRepository<ExpectedDelivery, UUID> {

    Page<ExpectedDelivery> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    Page<ExpectedDelivery> findAllByClientIdAndStatusOrderByCreatedAtDesc(
            UUID clientId, ExpectedDeliveryStatus status, Pageable pageable);

    /**
     * Read by id AND client, never by id alone: layer 2 of the tenant isolation
     * {@code TenantAwareEntity} documents, which holds even if the Hibernate filter is ever left
     * disabled.
     */
    Optional<ExpectedDelivery> findByIdAndClientId(UUID id, UUID clientId);

    /**
     * How much of each product is still expected, as rows of {productId, outstanding}. One query
     * for a whole page of products - the same N+1 the batched preferred-vendor read avoids.
     *
     * <p>Only OPEN expectations count, and only the part not yet received. A line that has arrived
     * in full contributes nothing, and an over-delivery contributes nothing rather than a negative
     * that would quietly cancel out another line's genuine shortfall.
     */
    @Query("select l.product.id, sum(l.quantity - l.receivedQuantity) from ExpectedDeliveryLine l "
            + "where l.expectedDelivery.clientId = :clientId "
            + "and l.expectedDelivery.status = com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus.OPEN "
            + "and l.quantity > l.receivedQuantity "
            + "and l.product.id in :productIds "
            + "group by l.product.id")
    List<Object[]> outstandingByProduct(UUID clientId, List<UUID> productIds);
}
