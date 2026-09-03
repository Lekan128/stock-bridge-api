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
import lombok.Builder;
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
 *
 * <h2>V20: when it happened, and which import wrote it</h2>
 * {@link #occurredAt} separates <em>when the delivery happened</em> from {@link #createdAt},
 * which stays what it always was - when this row was written. Read {@link #occurredAt}'s own
 * javadoc before using either: FIFO now orders by {@code (occurredAt, createdAt)}, and the
 * distinction is load-bearing rather than cosmetic. {@link #importBatchId} stamps the {@link
 * ImportSession} whose commit wrote the row, which is what makes "show me what this import
 * created" and the undo of BULK_IMPORT_DESIGN.md section 6.6 possible at all.
 *
 * <h2>V21: what was typed, kept beside what was recorded</h2>
 * {@link #enteredUnit}, {@link #enteredQuantity} and {@link #enteredUnitPrice} are the entry as
 * the human wrote it - "20 bags at &#8358;45,000/bag" - while {@link #quantity} and
 * {@link #unitPriceAtTime} keep their existing meaning exactly: base units, and money per stock
 * unit. Read {@link #enteredUnit}'s javadoc before touching any of the three; they are display
 * facts, and UNIT_UX_CONTRACT.md section 3.3 forbids reading them to compute a balance or a cost.
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

    /**
     * When this movement actually HAPPENED - the delivery date, not the data-entry date. Added
     * V20; see BULK_IMPORT_DESIGN.md section 8.4.
     *
     * <h2>Why this is a separate column and not a reuse of createdAt</h2>
     * {@link #createdAt} is, and stays, <b>the immutable audit fact of when this row was
     * WRITTEN</b>. That is a genuinely different question from when the goods moved, and before
     * V20 the two were conflated because nothing could record a past event: every movement was
     * entered as it happened, so "written" and "happened" were the same instant and it cost
     * nothing to pretend they were the same fact.
     *
     * <p>Bulk stock-in breaks that, and not at the margins - it breaks it in its primary use
     * case. Its whole stated purpose is "record what we bought outside the platform", so a
     * typical file is last month's purchases entered today. Inserted with today's timestamp,
     * those lots sort AFTER stock that was genuinely received earlier, and FIFO - which consumes
     * lots oldest-first - draws in the wrong order. The damage is not just a wrong number: the
     * per-delivery trace {@link StockMovementAllocation} exists to provide (the recall, the
     * dispute, the "which of your deliveries did this bad batch come from") then points at the
     * wrong delivery, confidently.
     *
     * <h2>Ordered by (occurredAt, createdAt), and createdAt is why the pair works</h2>
     * {@code StockMovementRepository.findInMovementsForUpdate} orders by this column first and
     * {@link #createdAt} second. The tiebreak is not an afterthought: a spreadsheet routinely
     * gives a whole delivery the same date (often midnight, since a date cell has no time), so
     * ties are the common case rather than the rare one, and an unstable order among tied lots
     * would make FIFO non-deterministic between two reads of the same data. {@link #createdAt}
     * is the right tiebreak precisely because it is the write-time fact - it never ties (it is
     * generated per row), it never changes, and "the order they were entered in" is a defensible
     * answer to give a user asking why one same-day lot was drawn before another.
     *
     * <h2>Not in the future, with a day of grace</h2>
     * Enforced by {@code chk_stock_movements_occurred_at_not_future} and, with a clearer
     * message, by the service layer before it gets there. The day of grace is clock and timezone
     * skew rather than slack - a delivery entered as "today" from a device an hour ahead is
     * routinely a few hours past the server's {@code now()} through nobody's fault, and refusing
     * it would produce an error message that is simply false from where the user is sitting.
     * The other half of section 8.4 - warn, do not block, for dates far in the PAST - is
     * deliberately not a constraint: that is a warning on a review row, and a warning is
     * something a service produces, not something a database refuses.
     *
     * <h2>updatable = false, like every other column here</h2>
     * A delivery date recorded wrongly is corrected the way every other ledger mistake is: by
     * posting a compensating movement, not by editing history. The {@code @Builder.Default}
     * below is the "null means now" rule of {@code StockInRequest.occurredAt} expressed once, so
     * that a caller with nothing to say about timing - a stock-out, an adjustment, an order
     * receipt - simply does not mention it and gets the honest answer.
     */
    @Builder.Default
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt = OffsetDateTime.now();

    /**
     * The {@link ImportSession} whose commit wrote this movement, or null for anything recorded
     * by hand, by an order receipt, or by the pre-V20 paths. Added V20; see
     * BULK_IMPORT_DESIGN.md section 6.5 ("Every entity written by a commit carries the
     * session_id as import_batch_id").
     *
     * <p>This is what "Undo this import" reads to find the lots a batch created - and, when any
     * of them has already been drawn from (i.e. has {@link StockMovementAllocation} rows), what
     * lets the refusal name the exact three deliveries that block it ("3 of these 40 deliveries
     * have already been sold from") instead of failing vaguely. Design doc 6.6 is explicit that
     * an undo never deletes ledger rows: the reversal is a compensating {@code ADJUSTMENT}, or a
     * clean refusal naming what blocks it.
     *
     * <p>A raw UUID rather than a mapped {@code @ManyToOne}, unlike {@link #companyVendor} above
     * and despite there being a real foreign key behind it. Two reasons. This is an append-only
     * ledger row that is read in bulk - by analytics, by the movement history, by FIFO - and
     * none of those reads want an association hanging off it that a stray {@code getImportBatch()}
     * could turn into an N+1 over sessions. And the one query that does care ("everything this
     * import created") filters on the id itself, which a raw column serves exactly as well. The
     * database still enforces the reference, {@code ON DELETE RESTRICT} - a session that wrote
     * ledger rows is not garbage to be collected.
     */
    @Column(name = "import_batch_id", updatable = false)
    private UUID importBatchId;

    /**
     * The unit code the human actually typed this delivery in - {@code "BAG"}, {@code "T"} -
     * or NULL when they typed it in the product's own stock unit (which is what every request
     * that omits {@code unit} says). Added V21; UNIT_UX_CONTRACT.md section 3.3.
     *
     * <h2>Display fact, never an input to a calculation</h2>
     * This and its two siblings below exist so a receipt, a history row or an audit can show
     * <em>what was entered</em> alongside <em>what was recorded</em> - contract non-negotiable 3,
     * "what the user typed and what the ledger records appear together". They are deliberately
     * NOT part of any balance or cost: {@link #quantity} stays the base-unit quantity and
     * {@link #unitPriceAtTime} stays the per-stock-unit price, and those two remain the only
     * numbers {@code Product.quantityOnHand}, the weighted-average cost, FIFO and
     * {@link StockMovementAllocation} are computed from. Section 3.3 states the rule flatly:
     * "nothing may read them to compute a balance or a cost." A second quantity that some code
     * path might sum is how a ledger acquires two answers to the same question.
     *
     * <p>This is NetSuite's model rather than an invention - store the transaction in the base
     * unit, keep the entered unit beside it, show what was typed, compute on the base.
     *
     * <h2>Every existing row is NULL, and NULL has a defined meaning</h2>
     * The V21 migration backfills nothing, because there is nothing honest to backfill: a
     * movement written before this column existed did not record what unit its number was typed
     * in. NULL therefore reads as "entered in the stock unit", and every consumer falls back to
     * {@link #quantity}/{@link #unitPriceAtTime} - which is not a guess, it is exactly what those
     * rows meant, since before this remediation a converted quantity was the only thing ever
     * stored.
     *
     * <p>{@code updatable = false} like every other column on this append-only row: what somebody
     * typed on a Tuesday is not a fact that later becomes a different fact.
     */
    @Column(name = "entered_unit", length = 32, updatable = false)
    private String enteredUnit;

    /**
     * The number the human typed, counted in {@link #enteredUnit} - 20, where {@link #quantity}
     * says 1000. Null exactly when {@link #enteredUnit} is; see its javadoc for why nothing may
     * compute from this. Added V21.
     *
     * <p>Decimal rather than integer, unlike {@link #quantity}: an entered quantity is not
     * constrained to whole stock units (that is the entire point of entering in another unit),
     * and a future half-tonne entry should not have to round before it is even recorded. Scale 3
     * matches {@code numeric(14,3)} in V21.
     */
    @Column(name = "entered_quantity", precision = 14, scale = 3, updatable = false)
    private BigDecimal enteredQuantity;

    /**
     * The price the human typed, <b>per {@link #enteredUnit}</b> - &#8358;45,000 per bag, where
     * {@link #unitPriceAtTime} says &#8358;900 per kg. Null when no price was given or when the
     * entry was in the stock unit. Added V21.
     *
     * <p>The pair of prices is the visible half of the P0-1 fix
     * (UNIT_UX_REMEDIATION_PLAN.md section 3): before this, only the typed number was stored and
     * it was stored in the per-stock-unit column, so &#8358;45,000 per bag became &#8358;45,000
     * per kg with nothing on the row to reveal it. Now the converted figure is what the ledger
     * uses and the typed figure sits beside it, so the two can be shown together and any future
     * drift between them is visible rather than silent.
     */
    @Column(name = "entered_unit_price", precision = 14, scale = 2, updatable = false)
    private BigDecimal enteredUnitPrice;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
