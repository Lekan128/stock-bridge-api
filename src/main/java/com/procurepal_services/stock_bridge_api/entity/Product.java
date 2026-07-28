package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A tenant's catalog/inventory item. Extends TenantAwareEntity for client_id
 * - see that class for how isolation is enforced. sku is unique per tenant
 * only (see the (client_id, sku) constraint in the schema), not globally.
 *
 * quantityOnHand is intentionally not writable through the product update
 * endpoint - stock levels are expected to change only through stock_movements
 * (a later step), keeping the audit trail authoritative for inventory counts.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Product extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sku;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "cost_price", precision = 14, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * Marketplace category. Safe as a mapped association because ProductCategory
     * is global (not tenant-scoped), so no tenant filter applies to it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    /**
     * Listed on the public marketplace catalog. Only ever true on the PLATFORM
     * OWNER's products - a CHECK constraint cannot express that (it needs a join
     * to clients.is_platform_owner), so it is enforced by the listing endpoint's
     * platform-owner guard and, defensively, by every public catalog query also
     * filtering on the platform owner's client_id.
     */
    @Column(name = "is_marketplace_listed", nullable = false)
    private boolean marketplaceListed;

    /** How the item is actually traded: 'bag (50kg)', 'carton (24)', 'keg (25L)'. */
    @Column(name = "unit_of_measure", length = 50)
    private String unitOfMeasure;

    /**
     * Minimum purchasable quantity, mirroring the column default of 1 so an
     * ordinary tenant product built by the existing product endpoints (which know
     * nothing about the marketplace) never lands on 0 and trips the
     * min_order_quantity >= 1 CHECK.
     */
    @Builder.Default
    @Column(name = "min_order_quantity", nullable = false)
    private int minOrderQuantity = 1;

    @Column(length = 120)
    private String brand;

    /** URL-facing identifier for the storefront, unique per tenant where present. */
    @Column(length = 160)
    private String slug;

    /**
     * Paid for but not yet received, and therefore NOT usable stock. Deliberately
     * a column on the product rather than a StockMovement: no goods have moved
     * yet, and writing an IN movement at payment time would corrupt both
     * quantityOnHand and the audit trail. The movement is written when the buyer
     * confirms receipt, which is when stock actually arrives.
     */
    @Column(name = "incoming_quantity", nullable = false)
    private int incomingQuantity;

    /**
     * The ProcurePal catalog product this row was created from, for a buyer's own
     * inventory item. This is what makes reorder and per-product analytics work
     * without matching on names.
     *
     * A raw UUID rather than a self-association on purpose: it points across
     * tenants (buyer's row -> platform owner's row), and Product is tenant-scoped,
     * so a mapped association would be filtered out from under the caller. Load
     * the target explicitly with the platform owner's client_id.
     */
    @Column(name = "source_product_id")
    private UUID sourceProductId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
