package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends TenantScopedRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {

    /**
     * Whether this product has ever had a movement recorded. Backs the V19 rule that {@code
     * Product.unitOfMeasure} becomes immutable once true - see {@code Product}'s javadoc on
     * that field and {@code ProductManagementService.update}'s guard.
     */
    @Query("SELECT COUNT(m) > 0 FROM StockMovement m WHERE m.product.id = :productId AND m.clientId = :clientId")
    boolean existsByProductIdAndClientId(@Param("productId") UUID productId, @Param("clientId") UUID clientId);

    /**
     * Every IN movement (lot) for a product, oldest first, row-locked for the duration of the
     * caller's transaction - the concurrency guard MULTI_VENDOR_INVENTORY_DESIGN.md section
     * 5.2a's "Concurrency and oversell" paragraph requires: two stock-outs racing for the same
     * lot must not both read the same "remaining" balance and both spend it. A second concurrent
     * {@code stockOut} blocks here until the first transaction commits (or rolls back), then
     * sees the true post-allocation remaining balance rather than a stale one.
     *
     * <p>Locks every IN movement for the product, not just ones with balance remaining -
     * "remaining" is itself derived from {@code StockMovementAllocationRepository.
     * sumQuantityByInMovementId} and cannot be filtered in this query without a correlated
     * subquery per row; simplicity and correctness were chosen over trimming an already-small
     * per-product row set (inventory volumes here are not high enough for full-table lot counts
     * to be a real contention concern - see {@code StockManagementService}'s own class javadoc
     * for the same tradeoff made for the product-row lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM StockMovement m WHERE m.product.id = :productId AND m.clientId = :clientId "
            + "AND m.movementType = com.procurepal_services.stock_bridge_api.entity.MovementType.IN "
            + "ORDER BY m.createdAt ASC")
    List<StockMovement> findInMovementsForUpdate(@Param("productId") UUID productId, @Param("clientId") UUID clientId);

    /**
     * Rows with a null unit_price_at_time (adjustments, or IN/OUT recorded
     * without a price) are excluded from value sums by the "unitPriceAtTime IS
     * NOT NULL" predicate below - they still count toward sumQuantity, since
     * that's units moved regardless of whether a price was recorded. This is
     * why totalUnitsIn/Out in a summary can be nonzero while totalInValue/OutValue
     * is smaller than "quantity * a typical price" would suggest.
     */
    @Query("SELECT COALESCE(SUM(m.quantity * m.unitPriceAtTime), 0) FROM StockMovement m "
            + "WHERE m.clientId = :clientId AND m.movementType = :movementType AND m.unitPriceAtTime IS NOT NULL "
            + "AND m.createdAt BETWEEN :from AND :to")
    BigDecimal sumValue(
            @Param("clientId") UUID clientId,
            @Param("movementType") MovementType movementType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m "
            + "WHERE m.clientId = :clientId AND m.movementType = :movementType "
            + "AND m.createdAt BETWEEN :from AND :to")
    long sumQuantity(
            @Param("clientId") UUID clientId,
            @Param("movementType") MovementType movementType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /**
     * Native (Postgres-specific date_trunc/to_char) rather than JPQL - portable
     * date-bucketing isn't expressible in JPQL, and this is a Postgres-only app.
     * granularity is bound as a parameter but is validated against a fixed
     * allow-list (day/week/month) by AnalyticsService before this is called, so
     * it's never attacker-controlled free text reaching date_trunc.
     * GROUP BY/ORDER BY reference the "period" output-column alias rather than
     * repeating the date_trunc(...) expression - Postgres allows this, and it
     * sidesteps GROUP BY needing an expression syntactically identical to the
     * one in SELECT (to_char(date_trunc(...)) doesn't match a bare date_trunc(...)).
     * Each row: [period(text), inValue(numeric), outValue(numeric), inQuantity(bigint), outQuantity(bigint)].
     */
    @Query(
            value = "SELECT to_char(date_trunc(:granularity, created_at), 'YYYY-MM-DD') AS period, "
                    + "COALESCE(SUM(CASE WHEN movement_type = 'IN' AND unit_price_at_time IS NOT NULL "
                    + "THEN quantity * unit_price_at_time ELSE 0 END), 0) AS in_value, "
                    + "COALESCE(SUM(CASE WHEN movement_type = 'OUT' AND unit_price_at_time IS NOT NULL "
                    + "THEN quantity * unit_price_at_time ELSE 0 END), 0) AS out_value, "
                    + "COALESCE(SUM(CASE WHEN movement_type = 'IN' THEN quantity ELSE 0 END), 0) AS in_quantity, "
                    + "COALESCE(SUM(CASE WHEN movement_type = 'OUT' THEN quantity ELSE 0 END), 0) AS out_quantity "
                    + "FROM stock_movements "
                    + "WHERE client_id = :clientId AND created_at BETWEEN :from AND :to "
                    + "GROUP BY period "
                    + "ORDER BY period",
            nativeQuery = true)
    List<Object[]> movementsOverTime(
            @Param("clientId") UUID clientId,
            @Param("granularity") String granularity,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** Each row: [productId(uuid), name(text), sku(text), totalValue(numeric), totalQuantity(bigint)]. */
    @Query(
            value = "SELECT p.id, p.name, p.sku, "
                    + "COALESCE(SUM(CASE WHEN m.unit_price_at_time IS NOT NULL THEN m.quantity * m.unit_price_at_time ELSE 0 END), 0) AS total_value, "
                    + "COALESCE(SUM(m.quantity), 0) AS total_quantity "
                    + "FROM stock_movements m JOIN products p ON p.id = m.product_id "
                    + "WHERE m.client_id = :clientId AND m.movement_type = :movementType AND m.created_at BETWEEN :from AND :to "
                    + "GROUP BY p.id, p.name, p.sku "
                    + "ORDER BY total_value DESC "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topProductsByValue(
            @Param("clientId") UUID clientId,
            @Param("movementType") String movementType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);

    @Query(
            value = "SELECT p.id, p.name, p.sku, "
                    + "COALESCE(SUM(CASE WHEN m.unit_price_at_time IS NOT NULL THEN m.quantity * m.unit_price_at_time ELSE 0 END), 0) AS total_value, "
                    + "COALESCE(SUM(m.quantity), 0) AS total_quantity "
                    + "FROM stock_movements m JOIN products p ON p.id = m.product_id "
                    + "WHERE m.client_id = :clientId AND m.movement_type = :movementType AND m.created_at BETWEEN :from AND :to "
                    + "GROUP BY p.id, p.name, p.sku "
                    + "ORDER BY total_quantity DESC "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topProductsByQuantity(
            @Param("clientId") UUID clientId,
            @Param("movementType") String movementType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);
}
