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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

    /**
     * Selling price. Required only for a product belonging to a SELLING tenant -
     * {@link ClientType#VENDOR}, or the platform owner acting as a seller - and
     * that requirement is NOT enforced here: a CHECK/NOT NULL on this column
     * cannot tell a company's row from a vendor's without a join, the same
     * reason {@link #marketplaceListed}'s seller-only rule lives outside a
     * constraint too. It is enforced by the service layer instead.
     *
     * <p>Nullable since V17. A buying company adding stock it BOUGHT has no
     * selling price to give and may now leave this blank; a vendor's row is
     * still expected to always carry one. Existing rows are unaffected - this
     * only removes the requirement going forward.
     */
    @Column(name = "unit_price", precision = 14, scale = 2)
    private BigDecimal unitPrice;

    /**
     * What this stock costs us, as money <b>per ONE {@link #unitOfMeasure}</b> - per kg for a
     * product counted in kg, never per bag. UNIT_UX_CONTRACT.md section 3.2 pins that basis, and
     * anything rendering this figure must state it (non-negotiable 2).
     *
     * <h2>A weighted average, recalculated on every receipt</h2>
     * {@code newCost = (oldQty × oldCost + inQty × inPrice) / (oldQty + inQty)}, computed by
     * {@code StockManagementService.recomputeWeightedAverageCost} on every stock-in
     * (MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3). Both quantities in that formula are counts
     * of {@link #unitOfMeasure} and both prices are per one of them - the four numbers only
     * blend into a meaningful fifth while all of them share a basis.
     *
     * <h2>The basis used to be whatever the last delivery happened to be typed in</h2>
     * Before V21 the incoming price reached that formula exactly as the user typed it while the
     * incoming quantity reached it converted, so a delivery entered as "20 bags at &#8358;45,000
     * per bag" on a 50 kg-bag product set this column to &#8358;45,000 per kg - fifty times the
     * truth, written silently and then averaged into every later delivery, so it never washed
     * out (UNIT_UX_REMEDIATION_PLAN.md section 3, P0-1). Values written through that path cannot
     * be told apart from correct ones after the fact; the remediation plan's Phase 0 is explicit
     * that they are to be REPORTED for a human to resolve, never auto-corrected.
     *
     * <p>Nullable: a product may have no cost on record at all (nothing has ever been received
     * for it, and nobody has typed one). A receipt with no price leaves this untouched rather
     * than blending a null in as zero.
     */
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
     * Listed on the public marketplace catalog.
     *
     * <p>Only ever true on the products of a client that may SELL - since V11 that
     * means the platform owner OR any {@link Client} with
     * {@link ClientType#VENDOR}, not the platform owner alone as it did before. A
     * CHECK constraint still cannot express it (it needs a join to clients), so it
     * is enforced by the listing endpoint's guards - {@code PlatformOwnerGuard}
     * and {@code VendorGuard}, whose {@code requireSeller()} is exactly this
     * question - and defensively by catalog queries filtering on a seller's
     * client_id rather than trusting this flag alone.
     *
     * <p>Note what changed for readers: a listed product's client_id is no longer
     * a synonym for "the platform owner". Code that used the two
     * interchangeably - and there was no reason not to before V11 - is now wrong.
     */
    @Column(name = "is_marketplace_listed", nullable = false)
    private boolean marketplaceListed;

    /**
     * What this product is fundamentally MEASURED in - a weight, volume, length, or (for
     * uncounted discrete goods) the generic "piece". Must resolve to a
     * {@code product.unit.UnitOfMeasure} constant whose
     * {@code product.unit.UnitOfMeasureRole} is {@code BASE} (see that enum for why the split
     * exists).
     *
     * <h2>V18: this is now one of THREE related fields, not one of two</h2>
     * V17 paired this with a single numeric count and called that "how much one unit is" -
     * but that conflated two different questions a B2B product actually needs answered
     * separately: what it is measured in, and how it is packaged/sold. V18 splits the second
     * question out to {@link #packagingUnit} + {@link #packagingSize}, so "a 50kg bag" is now
     * expressed as unitOfMeasure=KG (this field), packagingUnit=BAG, packagingSize=50 - all
     * three together, not unitOfMeasure=BAG with a bare count and no unit. This field alone,
     * with the other two null, is still valid and means "sold loose" - e.g.
     * unitOfMeasure=LITER with no packaging at all.
     *
     * <p>Column mapping is unchanged by V18 - still a free VARCHAR(50), no CHECK. The
     * application validates a new value against the fixed list in
     * {@code product.unit.UnitOfMeasure} (see its {@code fromCode}, role-checked for BASE by
     * {@code ProductManagementService.resolveUnitOfMeasure}) rather than the schema,
     * specifically so existing free-text rows are never broken and so a tenant can still
     * request a unit that isn't on the list yet (a separate module).
     *
     * <h2>V19: immutable once the product has any StockMovement</h2>
     * This is the unit every historical {@link StockMovement} quantity is implicitly recorded
     * in. Changing it after stock has moved would silently REINTERPRET every past movement
     * rather than converting anything - a product with 100 units on the books, changed from KG
     * to BAG, would still say "100" but now mean something 50x larger. Enforced by the service
     * layer ({@code ProductManagementService.update}, via a {@code StockMovementRepository}
     * existence check), not a DB constraint - the same reasoning every other cross-row rule in
     * this class already uses. {@link #packagingUnit}/{@link #packagingSize} are NOT covered by
     * this rule and stay editable any time - they are a default shortcut for the stock-in form,
     * never load-bearing for a past record the way the base unit is.
     */
    @Column(name = "unit_of_measure", length = 50)
    private String unitOfMeasure;

    /**
     * How this product is packaged/sold, if at all - a Bag, Carton, Box and similar. Must
     * resolve to a {@code product.unit.UnitOfMeasure} constant whose
     * {@code product.unit.UnitOfMeasureRole} is {@code PACKAGING}.
     *
     * <p>Added by V18, alongside the rename of {@code unit_count} to
     * {@link #packagingSize this column's pair}. Nullable, and pairs both-or-neither with
     * {@link #packagingSize} - a packaging unit with no size, or a size with no unit, is
     * ambiguous rather than partially valid (see
     * {@code PackagingUnitAndSizeRequiredTogetherException}). Also requires
     * {@link #unitOfMeasure} to be non-null whenever this is set: {@link #packagingSize} is a
     * count of {@link #unitOfMeasure}, so packaging with no base unit to quantify is
     * meaningless (see {@code PackagingRequiresUnitOfMeasureException}). A product may have
     * {@link #unitOfMeasure} set with this null - sold loose, no packaging - but never the
     * reverse.
     */
    @Column(name = "packaging_unit", length = 50)
    private String packagingUnit;

    /**
     * How many of {@link #unitOfMeasure} one {@link #packagingUnit} holds, e.g.
     * unitOfMeasure="KG", packagingUnit="BAG", packagingSize=50 means "a 50kg bag";
     * unitOfMeasure="LITER", packagingUnit="KEG", packagingSize=0.5 means "a half-litre keg".
     * Decimal, not integer - Nigerian trade units are routinely fractional (half-bags,
     * litres). Nullable: meaningless without a {@link #packagingUnit} to quantify, which in
     * turn requires {@link #unitOfMeasure} - see {@link #packagingUnit}'s javadoc for both
     * pairing rules.
     *
     * <p>Renamed from {@code unitCount} by V18 (same column rename, same NUMERIC(14,2)
     * type/precision, no data migration) once its meaning narrowed from "how much one
     * unitOfMeasure is" to specifically "how much one packagingUnit is" - the old name no
     * longer described what the field means now that unitOfMeasure and packagingUnit are
     * separate axes.
     */
    @Column(name = "packaging_size", precision = 14, scale = 2)
    private BigDecimal packagingSize;

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

    /**
     * Every supplier this inventory item can be bought from, as entries in THIS company's own
     * vendor directory - the join table that replaced the single {@code company_vendor_id} FK
     * V11 added and V19 dropped. See {@link ProductVendor}'s class javadoc and
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1 for the full reasoning behind the change.
     *
     * <p>LAZY, and deliberately not eagerly joined or batch-fetched: most read paths (the
     * product list, low-stock, analytics) never touch a product's vendors at all, and forcing
     * every one of them to pay for loading this collection would be the N+1 this design
     * explicitly does not want. Load it explicitly (via {@code ProductVendorRepository}) on the
     * one screen that actually needs it - the Vendors tab - rather than through this
     * association. {@link #getPreferredVendor()} is the one place this codebase still navigates
     * it directly, and only because a single row is cheap regardless of collection size.
     */
    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private List<ProductVendor> vendors;

    /**
     * Where this product stands with listing moderation.
     *
     * <h2>What reads it</h2>
     * V11 added the column ahead of any code touching it, so that retrofitting
     * moderation onto a live third-party catalogue would never be necessary. The
     * workflow has since landed: {@code marketplace.moderation} owns the super
     * admin's review queue and the approve/reject decisions,
     * {@code ProductManagementService} stamps it at creation and returns an
     * APPROVED listing to PENDING when its identity fields change (which is also
     * the resubmission path), {@code MarketplaceProductSpecifications.listedBy}
     * requires APPROVED before a row reaches the public catalogue, and
     * {@code AdminCatalogProductResponse} publishes it so a seller can see why a
     * listing they switched on is still invisible.
     *
     * <p>Defaults to PENDING - fail closed, because the failure this exists to
     * prevent is an unmoderated vendor listing becoming a real company's purchase
     * order. Every row that predates V11 was backfilled to APPROVED. See
     * {@link ProductApprovalStatus} for why a buying company's own inventory rows
     * being PENDING is noise rather than a bug.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ProductApprovalStatus approvalStatus = ProductApprovalStatus.PENDING;

    /**
     * Why a reviewer refused this listing, shown to the vendor so they can fix it
     * and resubmit. Kept after a later approval rather than cleared: the history of
     * a contested listing is the first thing anyone asks for when it is disputed.
     */
    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    /**
     * The {@code super_admins} row that decided - not a {@code users} row.
     * Moderating a listing is a platform-operator action, and super admins are an
     * entirely separate identity from tenant users. A raw UUID for the same reason
     * {@link #sourceProductId} is one: it points outside this tenant.
     */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /**
     * The {@link ImportSession} whose commit created this product, or null for anything created
     * by hand, from an order receipt, or before V20. See BULK_IMPORT_DESIGN.md section 6.5:
     * "Every entity written by a commit carries the session_id as import_batch_id."
     *
     * <h2>What reads it</h2>
     * Two things, from opposite ends. The result screen's "View products" link
     * ({@code /app/products?importBatchId={id}}) - so a user who has just imported 42 rows can
     * see exactly what appeared, which is the difference between trusting the button and
     * checking the catalog by hand. And the undo of design doc 6.6, whose catalog half
     * deactivates the products a batch created and reverts the ones it updated to the {@code
     * raw} snapshot on their {@link ImportSessionRow} - blocked, per that section, for any
     * created product that has since had a {@link StockMovement}, because a product that has
     * moved stock is no longer cleanly reversible.
     *
     * <p>Set only on CREATE. An update row does not stamp this: the product was not created by
     * that import, and overwriting the stamp would make the earlier import's undo point at
     * nothing. This is also why the undo has to consult the row snapshots rather than this
     * column alone - "created by this batch" and "touched by this batch" are different sets, and
     * only the first one is a column.
     *
     * <p>A raw UUID rather than a mapped {@code @ManyToOne}, matching {@code
     * StockMovement.importBatchId} - see that field's javadoc for the reasoning. The database
     * still enforces the reference, {@code ON DELETE RESTRICT}.
     */
    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Whichever {@link ProductVendor} row currently holds {@code isPreferred = true} for this
     * product, or {@code null} if the product has no vendors at all. Computed from {@link
     * #vendors} rather than duplicating a foreign key on this class - see
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1: "Keep a convenience
     * Product.getPreferredVendor() accessor computed from the collection - don't duplicate the
     * FK."
     *
     * <p>Requires {@link #vendors} to already be loaded/initialized by the caller - this method
     * does not trigger a lazy fetch on its own if the collection was never touched inside an
     * open session, it only filters whatever is already there. Callers that need this outside a
     * session with the collection loaded should query {@code ProductVendorRepository} directly
     * instead.
     */
    public ProductVendor getPreferredVendor() {
        if (vendors == null) {
            return null;
        }
        return vendors.stream()
                .filter(ProductVendor::isPreferred)
                .findFirst()
                .orElse(null);
    }
}
