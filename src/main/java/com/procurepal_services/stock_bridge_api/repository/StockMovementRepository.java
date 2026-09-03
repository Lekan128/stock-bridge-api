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
     * Every IN movement (lot) for a product, <b>oldest-OCCURRING first</b>, row-locked for the
     * duration of the caller's transaction - the concurrency guard MULTI_VENDOR_INVENTORY_DESIGN.md
     * section 5.2a's "Concurrency and oversell" paragraph requires: two stock-outs racing for the same
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
     *
     * <h2>V20: ordered by (occurredAt, createdAt), not createdAt alone</h2>
     * "Oldest first" has to mean oldest DELIVERY, not oldest data entry, and before V20 those
     * were the same thing only because nothing could record a past event. Bulk stock-in's whole
     * purpose is recording purchases made outside the platform, so a typical file is last
     * month's deliveries entered today - and under a {@code createdAt}-only ordering every one
     * of those sorts after stock that genuinely arrived later, which is FIFO drawing in exactly
     * the wrong order, and the per-delivery trace {@link
     * com.procurepal_services.stock_bridge_api.entity.StockMovementAllocation} then confidently
     * naming the wrong delivery. See BULK_IMPORT_DESIGN.md section 8.4 and {@code
     * StockMovement.occurredAt}.
     *
     * <p>{@code createdAt} stays in the ORDER BY as the second key, and it is not decoration: a
     * spreadsheet gives a whole delivery one date (usually midnight, a date cell carrying no
     * time), so ties are the ordinary case here rather than the rare one. Without a tiebreak the
     * order among tied lots is whatever the plan happens to produce, which makes FIFO
     * non-deterministic between two reads of identical data - and "the order they were entered
     * in" is both stable and defensible to a user asking why one same-day lot went first.
     * {@code idx_stock_movements_product_id_type_occurred_at} covers this query end to end.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM StockMovement m WHERE m.product.id = :productId AND m.clientId = :clientId "
            + "AND m.movementType = com.procurepal_services.stock_bridge_api.entity.MovementType.IN "
            + "ORDER BY m.occurredAt ASC, m.createdAt ASC")
    List<StockMovement> findInMovementsForUpdate(@Param("productId") UUID productId, @Param("clientId") UUID clientId);

    /**
     * Everything one import's commit wrote to the ledger - BULK_IMPORT_DESIGN.md section 6.5's
     * {@code import_batch_id} stamp, read back. Two callers, from opposite ends: the result
     * screen's link to what a batch created, and the section 6.6 undo, which needs the lots so
     * it can check whether any has been drawn from before deciding between a compensating
     * {@code ADJUSTMENT} and a refusal that names the three deliveries that block it.
     *
     * <p>Ordered by {@code occurredAt} rather than {@code createdAt} so the undo's blocker list
     * reads in the same order as the file the user is looking at. Backed by the partial index
     * {@code idx_stock_movements_import_batch_id}.
     */
    List<StockMovement> findAllByClientIdAndImportBatchIdOrderByOccurredAtAsc(UUID clientId, UUID importBatchId);

    /**
     * Movements on this product that did NOT come from the given import - added by M4 for the
     * catalog undo in BULK_IMPORT_DESIGN.md section 6.6.
     *
     * <p>{@code existsByProductIdAndClientId} cannot answer the question undo actually asks. A
     * product created by an import with an opening balance ALWAYS has a movement - the one the
     * import itself wrote (section 3's fix) - so "has any movement" would block every undo of
     * the very case the feature was built for. What blocks an undo is a movement somebody else
     * made afterwards, which is this.
     */
    @Query("SELECT COUNT(m) FROM StockMovement m WHERE m.product.id = :productId AND m.clientId = :clientId "
            + "AND (m.importBatchId IS NULL OR m.importBatchId <> :importBatchId)")
    long countMovementsOutsideBatch(
            @Param("productId") UUID productId,
            @Param("clientId") UUID clientId,
            @Param("importBatchId") UUID importBatchId);


    /**
     * Every priced {@code IN} movement in a tenant, as {@code [productId (UUID),
     * companyVendorId (UUID or null), unitPriceAtTime (BigDecimal), packagingSize (BigDecimal or
     * null), enteredUnit (String or null)]}. Backs the one-off cost-basis audit of
     * UNIT_UX_REMEDIATION_PLAN.md Phase 0 - see {@code CostBasisAuditService}, which is the only
     * caller and reads nothing else from the ledger.
     *
     * <p>The last two columns are what make the audit work at all for a buying company. They are
     * not compared to anything - they are read as PROVENANCE: a row carrying a pack snapshot but
     * no {@code entered_unit} is, by construction, a delivery described in packs and recorded
     * before V21 existed to say so, which is precisely the path that stored a per-pack price in a
     * per-stock-unit column. See {@code CostBasisAuditService.SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX}.
     *
     * <p>One query for the whole tenant rather than one per product, because the audit's whole
     * job is to compare every product's stored cost against its own movement history: doing that
     * per product would be a full catalogue scan expressed as N round trips. The medians are
     * computed in Java rather than in SQL deliberately - Postgres has {@code percentile_cont},
     * but this is an append-only ledger read whose row count is bounded by the tenant's own
     * purchase history, and a native window function here would be the only such expression in
     * this repository for no gain a human running a report once would notice.
     *
     * <p>{@code OUT}/{@code ADJUSTMENT} rows are excluded: a sale's price is a SELLING price and
     * blending it into a cost comparison would be comparing two different economic facts.
     * Unpriced rows are excluded because a null is not a zero - a free sample must not drag a
     * median toward the floor and manufacture an anomaly that is not there.
     */
    @Query("SELECT m.product.id, m.companyVendor.id, m.unitPriceAtTime, m.packagingSize, m.enteredUnit "
            + "FROM StockMovement m WHERE m.clientId = :clientId "
            + "AND m.movementType = com.procurepal_services.stock_bridge_api.entity.MovementType.IN "
            + "AND m.unitPriceAtTime IS NOT NULL")
    List<Object[]> findPricedInMovementPricesForTenant(@Param("clientId") UUID clientId);

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
