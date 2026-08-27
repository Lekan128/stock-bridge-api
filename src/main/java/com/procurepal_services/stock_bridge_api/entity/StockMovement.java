package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An append-only ledger row for a single inventory change - see the
 * stock_movements table comment in V1__init_schema.sql. Never updated after
 * creation (hence every column but the inherited client_id is updatable =
 * false, and there's no updated_at), so quantity_on_hand can always be
 * reconstructed/audited from this table alone.
 *
 * quantity is a positive magnitude for IN/OUT, but a signed delta for
 * ADJUSTMENT (can be negative) - see V4__relax_stock_movements_quantity_constraint.sql
 * and StockManagementService for why.
 *
 * <h2>V19: an IN movement is now, itself, a lot record</h2>
 * {@link #companyVendor}, {@link #packagingUnit} and {@link #packagingSize} are snapshots of
 * what was actually delivered on THIS movement - the exact "freeze what happened at the time"
 * pattern {@link #unitPriceAtTime} already used, extended to vendor and packaging. A {@link
 * ProductVendor} row's own defaults can change later (a renegotiated packaging size, a
 * different default vendor) without rewriting history, because history lives here, not there.
 *
 * <p>{@link #companyVendor} is set on IN movements only - see its own javadoc for why OUT and
 * ADJUSTMENT deliberately leave it null. Together with {@link StockMovementAllocation}, this is
 * what makes an IN movement a full lot: vendor, quantity, price-at-time and packaging, plus
 * (via the allocation table) which later sales drew from it and by how much.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class StockMovement extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 20)
    private MovementType movementType;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price_at_time", precision = 14, scale = 2, updatable = false)
    private BigDecimal unitPriceAtTime;

    @Column(length = 1000, updatable = false)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    /**
     * Which supplier this delivery came from - IN movements only, added V19. Required by
     * {@code StockManagementService.stockIn} once the product already has any {@link
     * ProductVendor} row on file; a schema CHECK cannot express that (it would need a join).
     *
     * <p>Deliberately left null on OUT/ADJUSTMENT. Once {@link StockMovementAllocation} exists,
     * a single OUT can legitimately draw from more than one vendor's lots, so a column here
     * would either pick one arbitrarily or be redundant with the true breakdown - which is
     * always read through {@code StockMovementAllocation}, never this field.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "company_vendor_id", updatable = false)
    private CompanyVendor companyVendor;

    /**
     * Snapshot of how this delivery was packaged, e.g. "BAG" - same "freeze what happened at
     * the time" reasoning as {@link #unitPriceAtTime}. Nullable, and only ever meaningful on an
     * IN movement. Added V19.
     */
    @Column(name = "packaging_unit", length = 50, updatable = false)
    private String packagingUnit;

    /** Pairs with {@link #packagingUnit} - how many of the product's base unit it held. Added V19. */
    @Column(name = "packaging_size", precision = 14, scale = 2, updatable = false)
    private BigDecimal packagingSize;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
