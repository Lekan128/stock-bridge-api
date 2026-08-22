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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One line of an order, with the commercial terms frozen at checkout. Not a
 * TenantAwareEntity and has no client_id: a line belongs to its order, and the
 * order already records the buyer. That is also what lets ProcurePal read order
 * lines without lifting any tenant filter.
 *
 * The name/sku/unit/price/image snapshots are the point of this table. Renaming
 * or repricing a catalog product must not change what a past invoice says.
 *
 * productId (the platform owner's catalog product) and buyerProductId (the
 * buyer's own inventory row this fulfils into) are raw UUIDs on purpose - both
 * cross tenants relative to at least one of the two readers of this row. See
 * CartItem for the full rationale.
 *
 * receivedQuantity supports partial receipt, which is normal in wholesale: 8 of
 * 10 bags arrive today, 2 follow tomorrow. The unreceived remainder stays as
 * incoming stock on the buyer's product.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Set when the order reaches PLACED and the buyer's inventory row is found-or-created. */
    @Column(name = "buyer_product_id")
    private UUID buyerProductId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_sku", nullable = false, length = 100)
    private String productSku;

    @Column(name = "unit_of_measure", length = 50)
    private String unitOfMeasure;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /**
     * The platform's commission rate in force when this line was SOLD, as a
     * fraction - {@code 0.0750} is 7.5%. Resolved once from the seller's
     * {@link Client#getCommissionRate()} (or the platform default) at that moment
     * and never touched again.
     *
     * <p>It is a snapshot for exactly the reason the name, sku, unit and price
     * above are: renegotiating a vendor's rate must not retroactively rewrite what
     * the platform earned on every order they have already shipped, or a statement
     * printed before the change stops reconciling with one printed after it.
     *
     * <p>Nullable, and null means "no commission applies" - never zero by
     * coincidence. Every row that predates V11 is one of ProcurePal's own sales,
     * where the platform and the seller are the same party and commission is not a
     * concept, so inventing a rate for them would assert a commercial fact nobody
     * agreed.
     *
     * <h2>There is deliberately no commission AMOUNT here</h2>
     * Commission accrues on DELIVERY, not at checkout (VENDOR_RESEARCH.md Section
     * C item 2): a cancelled or returned order must never have earned the platform
     * anything. The amount is therefore a consequence of an event, and belongs in
     * the append-only vendor ledger a later module builds - where a return can post
     * a reversing entry against it. A computed amount stored here would be a second
     * place for money to live, and a way for the two to disagree after the first
     * refund.
     */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** How many units of this line are still owed - i.e. still sitting as incoming stock. */
    public int outstandingQuantity() {
        return quantity - receivedQuantity;
    }
}
