package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends TenantScopedRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {

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
