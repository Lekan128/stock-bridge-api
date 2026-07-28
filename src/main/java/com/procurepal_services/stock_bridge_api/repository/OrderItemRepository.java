package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain JpaRepository: order_items has no client_id (a line belongs to its order,
 * which already records the buyer), so nothing here is tenant-filtered. That is
 * what lets ProcurePal read order lines without lifting any filter - and it means
 * the caller must have already authorised access to the ORDER before calling
 * these. Resolve the order through OrderRepository first.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findAllByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Receiving a delivery accepts a list of (orderItemId, quantity) pairs from the
     * client, so every line must be re-checked against the order it is claimed to
     * belong to before its quantity is trusted.
     */
    Optional<OrderItem> findByIdAndOrderId(UUID id, UUID orderId);

    long countByOrderId(UUID orderId);

    /** Reorder / "you bought this before": every line ever bought for one catalog product. */
    List<OrderItem> findAllByProductId(UUID productId);
}
