package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Which {@code OUT} {@link StockMovement} consumed how much of which specific {@code IN}
 * movement (lot). This is what turns vendor-level FIFO into true per-shipment FIFO - every
 * {@code IN} movement is already an immutable, timestamped, vendor-and-price-tagged record once
 * V19's columns exist on {@link StockMovement}, so this table only has to record CONSUMPTION
 * against it. See MULTI_VENDOR_INVENTORY_DESIGN.md section 5.2a.
 *
 * <h2>Append-only, like StockMovement itself</h2>
 * No {@code updatedAt} - every column but the id and timestamp is {@code updatable = false}. A
 * single {@code OUT} of 20 bags may write two rows if it spans two lots (13 remaining from an
 * older delivery, 7 from the next); once written, a row is never edited.
 *
 * <h2>Remaining quantity in a lot is derived, never stored</h2>
 * {@code in_movement.quantity - SUM(allocations against it)} - purely computed from this table,
 * so it can never drift. See {@code StockMovementAllocationRepository} for the locked query
 * that reads candidate lots, and the class-level warning below about why that lock is not
 * optional.
 *
 * <h2>Concurrency - the one thing this design cannot leave implicit</h2>
 * Two stock-outs racing for the same lot can both read "10 remaining" and both try to allocate
 * 10 unless something stops them. The write path (({@code StockManagementService.stockOut})
 * must select candidate {@code IN} movements with a row lock ({@code SELECT ... FOR UPDATE},
 * i.e. {@code LockModeType.PESSIMISTIC_WRITE}) inside the same transaction that inserts
 * allocation rows and decrements {@link ProductVendor#getQuantityOnHandFromVendor()} - so a
 * second concurrent request blocks until the first commits and sees the true remaining balance,
 * never a lost-update race.
 *
 * <h2>Not a TenantAwareEntity</h2>
 * No {@code clientId}. Reached only through {@link #outMovement}/{@link #inMovement}, both
 * already tenant-scoped {@link StockMovement} rows - the same non-tenant-scoped-child pattern
 * {@link ProductVendorPriceTier} follows relative to {@link ProductVendor}. A direct lookup by
 * this row's id with no join back to one of those two would be a mistake regardless of tenancy.
 */
@Entity
@Table(name = "stock_movement_allocations")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class StockMovementAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The {@code OUT} row this allocation belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "out_movement_id", nullable = false, updatable = false)
    private StockMovement outMovement;

    /** The specific {@code IN} row (lot) drawn from. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "in_movement_id", nullable = false, updatable = false)
    private StockMovement inMovement;

    /**
     * How much of the {@link #inMovement} lot this {@link #outMovement} consumed. Integer,
     * matching {@link StockMovement#getQuantity()} - every quantity in this ledger is a whole
     * count of the product's base unit, and an allocation can never consume a fractional unit
     * of a lot that was itself received as a whole number.
     */
    @Column(nullable = false, updatable = false)
    private int quantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
