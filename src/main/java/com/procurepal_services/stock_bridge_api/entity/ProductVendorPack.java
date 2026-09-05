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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One priced offering a {@link ProductVendor} line makes for its product - a real pack (a
 * container and a size, e.g. Bag/50) or, when {@link #packagingUnit} is {@code null}, the bare
 * stock unit with no container. See MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-5 for the full
 * reasoning; this class states the JPA-level slice of it.
 *
 * <h2>Why this table exists</h2>
 * {@link ProductVendor} used to carry exactly one {@code defaultPackagingUnit}/
 * {@code defaultPackagingSize} pair, so a vendor selling the same item in two bag sizes had no
 * second row to put the other one in - and {@code product.unit.UnitOptions} deduplicates a
 * product's derived unit set by packaging code alone, so whenever that one pack happened to share
 * a code with the product's own pack but differ in size, it silently lost
 * (UNIT_UX_REMEDIATION_PLAN.md section 11.1, an accepted limitation until now). This table lets a
 * vendor line carry any number of packs instead of exactly zero or one - the same shape
 * {@link ProductVendor} itself gave {@link Product} for suppliers.
 *
 * <h2>NOT a {@code TenantAwareEntity}</h2>
 * Like {@link ProductVendorPriceTier}, this carries no {@code clientId}. It is reached only
 * through {@link #productVendor}, itself already a tenant-scoped row - a direct lookup by this
 * row's id with no join back to its {@link ProductVendor} would be a mistake regardless of
 * tenancy, so there is nothing for a second {@code client_id} predicate to add.
 *
 * <h2>A {@code null} {@link #packagingUnit} is a real, common state, not a half-filled pack</h2>
 * Not every vendor sells in a container - a vendor priced straight in the product's stock unit is
 * the common case, and it already has a cost/code/tiers today with no packaging at all. This is
 * the one place that case lives once cost and packaging share a table: both
 * {@link #packagingUnit} and {@link #packagingSize} are {@code null} together, never one without
 * the other (enforced at the database, not only here). {@code product.unit.UnitOptions} simply
 * skips such a row when building a unit set - it names no container, so it carries no conversion
 * factor - and reads it only for {@link #vendorSku}/{@link #lastCostPrice}, the same way it
 * already read {@code ProductVendor.lastCostPrice} before this table existed.
 *
 * <h2>{@code isDefault} is a swap, never a bare set</h2>
 * Exactly one row per {@code productVendorId} may hold {@code isDefault = true}, enforced by a
 * partial unique index (V24) rather than a CHECK, because the rule spans rows - the identical
 * convention {@link ProductVendor#isPreferred()} already uses one level up.
 */
@Entity
@Table(name = "product_vendor_packs")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ProductVendorPack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_vendor_id", nullable = false)
    private ProductVendor productVendor;

    /**
     * A PACKAGING-role {@code product.unit.UnitOfMeasure} code, or {@code null} meaning "priced
     * in the stock unit directly, no container" - see the class javadoc. Same fixed-list
     * validation {@code Product.packagingUnit} already has, enforced by the service layer rather
     * than a schema CHECK.
     */
    @Column(name = "packaging_unit", length = 50)
    private String packagingUnit;

    /** Pairs with {@link #packagingUnit} - null together, set together. */
    @Column(name = "packaging_size", precision = 14, scale = 2)
    private BigDecimal packagingSize;

    /**
     * That vendor's own code for THIS pack specifically. Moved off {@link ProductVendor} because
     * the same vendor's 25 kg and 50 kg bags of one item routinely carry different codes.
     */
    @Column(name = "vendor_sku", length = 100)
    private String vendorSku;

    /** Refreshed on every stock-in against this specific pack - see {@code StockManagementService}. */
    @Column(name = "last_cost_price", precision = 14, scale = 2)
    private BigDecimal lastCostPrice;

    /**
     * Which pack pre-fills the stock-in form and the Vendors tab's headline cost for this vendor.
     * Do not set this directly outside {@code ProductVendorService}'s swap logic - see the class
     * javadoc's "isDefault is a swap" section.
     */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
