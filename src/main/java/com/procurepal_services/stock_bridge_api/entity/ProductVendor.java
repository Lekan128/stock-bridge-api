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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One (product, supplier) line - the Odoo pricelist line / NetSuite item-vendor line that
 * replaces {@code products.company_vendor_id}, the single-FK bottleneck V11 introduced and
 * V19 removed. See MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1 for the full reasoning; this
 * class states the JPA-level slice of it.
 *
 * <h2>Why this is a join table and not a bigger FK</h2>
 * A tenant that buys the same real-world item from two suppliers used to need two
 * {@link Product} rows to record that, because {@code company_vendor_id} was a single
 * {@code @ManyToOne}. This table is what lets one {@link Product} carry any number of supplier
 * lines instead, each with its own packs (cost, packaging, vendor code - see
 * {@link ProductVendorPack}) and running quantity - the same shape Odoo's vendor pricelist and
 * NetSuite's "Multiple Vendors" feature both use.
 *
 * <h2>Tenant-scoped, and safe to map directly</h2>
 * Extends {@link TenantAwareEntity} like {@link Product}, {@link StockMovement} and
 * {@link CompanyVendor} - {@code clientId} is assigned from {@code TenantContext} on persist,
 * never accepted from a caller. Both {@link #product} and {@link #companyVendor} belong to the
 * SAME tenant by construction (a product can only be linked to a vendor in its own company's
 * directory), which is exactly the reasoning {@code Product.companyVendor}'s own javadoc gave
 * for being a safe mapped association rather than a raw UUID - it still applies here, one hop
 * further out.
 *
 * <h2>The two cached counters</h2>
 * {@link #quantityOnHandFromVendor} and {@link #totalQuantityReceived} are DISPLAY ROLLUPS,
 * not the ledger. The real source of truth for "how much is left from this specific delivery"
 * is {@link StockMovementAllocation} - see that class and {@code StockManagementService} for
 * where these two counters are written and why they can never drift further than a
 * reconciliation pass would catch. Same cached-not-authoritative status {@code
 * Product.quantityOnHand}'s own javadoc already documents for itself.
 *
 * <h2>isPreferred is a swap, never a bare set</h2>
 * Exactly one row per {@code productId} may hold {@code isPreferred = true}, enforced by a
 * partial unique index (V19) rather than a CHECK, because the rule spans rows. Flipping it on
 * for one vendor must atomically flip it off for whichever row held it before, in the same
 * transaction - see {@code companyvendor.ProductVendorService.update} and
 * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4 ("a swap, not a set"). There is deliberately no
 * operation that clears it to "no preferred vendor" at all.
 */
@Entity
@Table(name = "product_vendors")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ProductVendor extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_vendor_id", nullable = false)
    private CompanyVendor companyVendor;

    /**
     * NetSuite-style manual pin - which vendor defaults into the stock-in form when nothing else
     * disambiguates. See the class javadoc's "isPreferred is a swap, never a bare set" section;
     * do not set this directly outside {@code ProductVendorService.update}'s swap logic.
     */
    @Column(name = "is_preferred", nullable = false)
    private boolean isPreferred;

    /**
     * Cached DISPLAY ROLLUP only, for the Vendors tab. NOT what stock-out draws down against -
     * see {@link StockMovementAllocation} for the real ledger. Derived as SUM(this vendor's IN
     * movements) - SUM(allocations against those movements); bumped on every {@code stockIn} and
     * decremented on every lot-consuming {@code stockOut} that draws from this vendor's lots, in
     * the same transaction as the movement/allocation write.
     */
    @Column(name = "quantity_on_hand_from_vendor", nullable = false)
    private int quantityOnHandFromVendor;

    /**
     * Lifetime quantity received from this vendor. Derived as SUM(this vendor's IN movements)
     * alone - unlike {@link #quantityOnHandFromVendor}, never decremented by a sale.
     */
    @Column(name = "total_quantity_received", nullable = false)
    private int totalQuantityReceived;

    /**
     * This vendor's priced offerings - a real pack, or the bare stock unit, or both
     * (MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-5). Purely additive in the sense that most
     * vendor lines have exactly one; LAZY so listing a product's vendors never pulls every pack
     * (and its price tiers) along for the ride - load explicitly on the Vendors tab's expanded
     * row, the same discipline {@link ProductVendorPriceTier} already required one level down.
     */
    @OneToMany(mappedBy = "productVendor", fetch = FetchType.LAZY)
    private List<ProductVendorPack> packs;

    /**
     * Convenience accessor for a caller that has not been updated to think in terms of more than
     * one pack - the same "computed from the collection, not a duplicated FK" pattern this file's
     * own {@code Product.getPreferredVendor()} sibling already uses. {@code null} when this
     * vendor line has no pack on file at all yet, same as today.
     */
    public ProductVendorPack getDefaultPack() {
        if (packs == null) {
            return null;
        }
        return packs.stream().filter(ProductVendorPack::isDefault).findFirst().orElse(null);
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
