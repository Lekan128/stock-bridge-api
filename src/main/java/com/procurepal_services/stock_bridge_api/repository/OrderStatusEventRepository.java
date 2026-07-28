package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Not tenant-scoped, for the same reason as OrderItemRepository: an event belongs
 * to its order. Ascending by created_at because this is read as a timeline, and a
 * timeline that renders newest-first is a list, not a timeline.
 */
public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, UUID> {

    List<OrderStatusEvent> findAllByOrderIdOrderByCreatedAtAsc(UUID orderId);

    long countByOrderId(UUID orderId);
}
