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
 * One quantity-break price on a {@link ProductVendorPack} - "under 10 bags at X, 10+ at Y".
 * See MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1a and MULTI_PACK_PER_VENDOR_DESIGN.md
 * section 4.3.
 *
 * <h2>Purely additive</h2>
 * A pack with zero rows here is the common case and stays exactly as simple as before -
 * {@link ProductVendorPack#getLastCostPrice()} alone. This table only exists for the packs that
 * genuinely price by volume; most never get one.
 *
 * <h2>Why this hangs off the PACK, not the vendor (V24)</h2>
 * A tier is a property of a specific priced offering, and a pack is now that offering - "10+
 * bags of 50 kg" and "10+ bags of 25 kg" are different breaks a vendor might set independently.
 * Before V24 a vendor line had only ever had one pack, so keying a tier by
 * {@code product_vendor_id} or by {@code product_vendor_pack_id} meant the same thing; V24
 * re-pointed every existing row at its vendor's (sole, at the time) pack.
 *
 * <h2>NOT a TenantAwareEntity</h2>
 * Carries no {@code clientId}. It is reached only through {@link #productVendorPack}, itself
 * reached only through a tenant-scoped {@link ProductVendor} row - the same
 * non-tenant-scoped-child pattern {@code OrderItem} follows relative to {@code Order}. A direct
 * lookup by this row's id with no join back to its pack would be a mistake regardless of
 * tenancy, so there is nothing for a second client_id predicate to add.
 *
 * <h2>minQuantity is in the product's BASE unit, and is INCLUSIVE</h2>
 * Stored in the owning product's {@code unitOfMeasure}, not whatever unit a given purchase or
 * sale happens to be entered in - see {@link #minQuantity}'s own javadoc. A tier applies AT
 * exactly its {@code minQuantity} and above, matching how a human reads "10+ bags".
 */
@Entity
@Table(name = "product_vendor_price_tiers")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ProductVendorPriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_vendor_pack_id", nullable = false)
    private ProductVendorPack productVendorPack;

    /**
     * In the product's base {@code unitOfMeasure}, so tiers compare consistently regardless of
     * what unit a given purchase is entered in - a purchase entered in bags is converted to base
     * units before being checked against this column. INCLUSIVE: this tier's price applies at
     * exactly this quantity and above.
     */
    @Column(name = "min_quantity", nullable = false, precision = 14, scale = 2)
    private BigDecimal minQuantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
