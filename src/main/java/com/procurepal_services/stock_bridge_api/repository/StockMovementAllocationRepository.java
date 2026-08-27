package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.StockMovementAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code StockMovementAllocation} has no {@code client_id} of its own - see its class javadoc -
 * so every finder here is reached through one of the two movement ids, both already
 * tenant-scoped {@code StockMovement} rows. Callers must confirm the referenced movement
 * belongs to their tenant before trusting a result (or, for the locked-lots read, before
 * calling {@code StockMovementRepository.findInMovementsForUpdate}, which already takes
 * {@code clientId}).
 */
public interface StockMovementAllocationRepository extends JpaRepository<StockMovementAllocation, UUID> {

    /**
     * "What did this OUT draw from" - the receipt-style breakdown shown right after a sale
     * (MULTI_VENDOR_INVENTORY_DESIGN.md section 6). Oldest-drawn-lot first, i.e. insertion
     * order, since a stock-out writes its allocation rows in FIFO order.
     */
    List<StockMovementAllocation> findAllByOutMovementIdOrderByCreatedAtAsc(UUID outMovementId);

    /**
     * "What did this IN go on to fund" - {@code GET /stock-movements/{inMovementId}/allocations},
     * the recall/dispute trace MULTI_VENDOR_INVENTORY_DESIGN.md section 8/10 exists to answer.
     */
    List<StockMovementAllocation> findAllByInMovementIdOrderByCreatedAtAsc(UUID inMovementId);

    /**
     * How much of one specific lot has already been consumed, summed across every sale that
     * has ever drawn from it. Used together with that {@code IN} movement's own
     * {@code quantity} to compute what remains - see the class javadoc's "remaining quantity is
     * derived, never stored" section. Callers holding a lock on the {@code IN} movement row
     * (via {@code StockMovementRepository.findInMovementsForUpdate}) get a consistent read here
     * for the same reason: nothing else can insert a competing allocation against a locked lot
     * mid-transaction.
     */
    @Query("SELECT COALESCE(SUM(a.quantity), 0) FROM StockMovementAllocation a WHERE a.inMovement.id = :inMovementId")
    int sumQuantityByInMovementId(@Param("inMovementId") UUID inMovementId);
}
