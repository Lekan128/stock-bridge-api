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
