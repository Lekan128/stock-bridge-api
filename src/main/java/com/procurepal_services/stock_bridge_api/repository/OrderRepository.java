package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Order is tenant-scoped, so everything here is safe for a buyer by default:
 * findByIdForCurrentTenant / the client_id-carrying finders below hold even with
 * the Hibernate filter off, which is exactly what §6 of the marketplace contract
 * requires ("assert via client_id in the repository query, not just the filter").
 *
 * The platform owner's fulfilment queue is the one caller that needs to see other
 * tenants' rows. It must wrap its query in
 * {@code PlatformOwnerGuard.readAcrossTenants(...)} - the JpaSpecificationExecutor
 * methods and the findAllByStatus* finders here return nothing under ProcurePal's
 * own tenant filter otherwise. That guard asserts platform ownership before it
 * lifts the filter, so cross-tenant reads stay auditable in one place.
 */
public interface OrderRepository extends TenantScopedRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * Feeds order-number allocation (e.g. count of 'PP-2026-' rows + 1). A count is
     * good enough because uq_orders_order_number is the real guard: a concurrent
     * allocation collides on insert and the caller retries, rather than two orders
     * quietly sharing a number.
     */
    long countByOrderNumberStartingWith(String prefix);

    Page<Order> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    Page<Order> findAllByClientIdAndStatusOrderByCreatedAtDesc(
            UUID clientId, OrderStatus status, Pageable pageable);

    List<Order> findAllByClientIdAndStatusInOrderByCreatedAtDesc(UUID clientId, List<OrderStatus> statuses);

    long countByClientId(UUID clientId);

    long countByClientIdAndStatus(UUID clientId, OrderStatus status);

    /** Platform-owner fulfilment queue. Call inside readAcrossTenants(...). */
    Page<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    /**
     * The abandoned-checkout sweep: orders left at PENDING_PAYMENT past their
     * grace period get cancelled so they stop cluttering the buyer's list and
     * ProcurePal's queue.
     */
    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, OffsetDateTime createdBefore);

    /**
     * Lifetime spend for one customer, for the marketplace customers view. JPQL
     * (rather than a derived query) because the SUM has to COALESCE - a customer
     * with no paid orders must read as 0, not null.
     */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
            + "WHERE o.clientId = :clientId AND o.paymentStatus = :paymentStatus")
    BigDecimal sumTotalByClientIdAndPaymentStatus(
            @Param("clientId") UUID clientId, @Param("paymentStatus") PaymentStatus paymentStatus);
}
