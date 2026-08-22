package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One money event in a vendor's account. The foundation of this whole module: the
 * payout batch, the escrow figures and the vendor's statement are all queries over
 * this table and nothing else.
 *
 * <h2>Append-only, and enforced below the application</h2>
 * There is no setter on this class - {@code @Getter} only, no {@code @Setter} -
 * and that is not decoration. A ledger row is a claim about money that a vendor
 * may quote back at us months later; if it can be edited, "what did we tell them
 * in March" has no answer and every dispute becomes our word against theirs with
 * no evidence on either side. A correction is a NEW row with the opposite sign
 * naming the row it corrects ({@link #reversesEntryId}).
 *
 * <p>The immutability is stated three times over, deliberately, because each layer
 * catches a different mistake: no setters here (catches the ordinary call), every
 * column {@code updatable = false} (catches Hibernate deciding a loaded entity is
 * dirty), and {@code trg_vendor_ledger_entries_append_only} in V14 (catches the
 * data fix, the future admin screen and the route nobody has written yet). Same
 * reasoning V13 used for making "a vendor holds one user" a trigger rather than
 * an absence of permission.
 *
 * <h2>Signed from the vendor's point of view</h2>
 * Positive means the platform owes the vendor MORE. So {@code SUM(amount)} for a
 * seller is what we owe them, with no CASE expression and no per-type sign lookup
 * anywhere in the module - every balance here is that one sum with a different
 * WHERE clause. See {@link VendorLedgerEntryType}, which owns the convention and
 * is the only thing that turns a magnitude into a signed amount.
 *
 * <h2>One row per ORDER LINE, not per order</h2>
 * Because the commission rate is stamped per line ({@link OrderItem#getCommissionRate()})
 * and because a vendor checking a statement by hand needs to see the arithmetic
 * they would do themselves: this many units at this price, times this rate, is
 * this fee. An order-level row carrying a blended rate cannot be checked by
 * anybody, and "a vendor must be able to reproduce our arithmetic" is the actual
 * requirement this module was given.
 *
 * <h2>Not a TenantAwareEntity, and that is not an oversight</h2>
 * There is no {@code client_id} here. The rows are written from threads that
 * belong to somebody else - the accrual fires while the BUYER's tenant filter is
 * enabled, and the escrow sweep runs on a scheduler thread with no tenant at all -
 * so a filter keyed on the reader would be wrong at write time and would have to be
 * re-pointed on every path. Same arrangement, for a related reason, as
 * {@link Payment}.
 *
 * <p>What keeps one vendor out of another's money is therefore an explicit
 * predicate, exactly as it is in the vendor analytics module: every read binds a
 * seller id that came from {@code VendorGuard.requireSeller()} and never from a
 * request parameter. There is no second layer to fall back on here, so the
 * predicate is the entire isolation story - see {@code VendorLedgerService}.
 *
 * <h2>Raw UUIDs rather than associations</h2>
 * {@link #orderId} and {@link #orderItemId} point at tenant-scoped rows belonging
 * to the BUYER, while this row belongs to the SELLER. A mapped association would
 * be loaded under whichever tenant filter happened to be active and would silently
 * resolve to null for the reader who most needs it. Same trap {@link CartItem} and
 * {@link Order} document at length.
 */
@Entity
@Table(name = "vendor_ledger_entries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Whose ledger this row belongs to: a vendor's clients id. */
    @Column(name = "seller_client_id", nullable = false, updatable = false)
    private UUID sellerClientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 30)
    private VendorLedgerEntryType entryType;

    /** Signed. See the class doc and {@link VendorLedgerEntryType#normalise(BigDecimal)}. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /**
     * The order this row is about. Null on {@code PAYOUT} and only there: a payout
     * settles a fortnight of orders, so naming one of them would be a lie.
     */
    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    /** The line this row is about. Null exactly when {@link #orderId} is. */
    @Column(name = "order_item_id", updatable = false)
    private UUID orderItemId;

    /**
     * The batch that PRODUCED this row - set on {@code PAYOUT} and nothing else.
     *
     * <p>Note what this is NOT: it is not "the batch that settled this line". That
     * would require updating a ledger row when a batch claims it, which this table
     * does not permit, so membership lives in {@code vendor_payout_batch_lines}
     * instead.
     */
    @Column(name = "payout_batch_id", updatable = false)
    private UUID payoutBatchId;

    /**
     * The row this row corrects. Present on exactly the two reversal kinds, which
     * is what makes a correction auditable rather than merely opposite: without it
     * a negative figure in March could be a reversal, a penalty or a typo, and only
     * the memo would say which.
     */
    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    /**
     * The rate applied, on the row that applied it. Present on exactly the two
     * commission kinds.
     *
     * <p>Copied here rather than read back through {@link OrderItem} so a statement
     * is self-contained arithmetic a vendor can check without also being shown the
     * order line. It is a snapshot of a snapshot, and both exist for the same
     * reason V11 gave: a figure on an invoice must keep saying what it said.
     */
    @Column(name = "commission_rate", updatable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /** What the rate was applied to. Travels with {@link #commissionRate}, always. */
    @Column(name = "basis_amount", updatable = false, precision = 14, scale = 2)
    private BigDecimal basisAmount;

    /**
     * When the money event HAPPENED, which is not when the row was written.
     * Statements are ordered and windowed on this.
     *
     * <p>Separate from {@link #createdAt} because the escrow sweep can post an
     * accrual days after the delivery it is accruing for, and a statement that
     * filed that entry under the sweep's run date would not reconcile against the
     * vendor's own delivery records.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /**
     * When this row becomes PAYOUT-ELIGIBLE - {@code occurredAt} plus the escrow
     * hold that was in force at accrual (M9). Never null.
     *
     * <h2>The rule this column encodes, in one sentence</h2>
     * <b>The hold that applies to a sale is the hold that was in force when the
     * buyer confirmed it; changing the setting affects FUTURE accruals only.</b>
     * That is the first question an operator asks about this feature, so it is
     * answered on the column that decides it.
     *
     * <p>Storing it rather than computing {@code occurredAt + currentHold} at query
     * time is the whole point. A computed predicate would make a settings change
     * retroactive: raise the hold from 7 to 30 and every vendor's already-confirmed
     * money silently moves three weeks further away, on a date they had already
     * been shown, with no row anywhere recording that it moved.
     *
     * <p>And note that {@code updatable = false} plus the append-only trigger make
     * the stored value not merely un-updated but UN-UPDATABLE - which is what makes
     * storing it safe rather than merely convenient. The fact it records (what the
     * terms were when the money was earned) can never change, so neither can it.
     *
     * <h2>Maturity is not the payout cadence</h2>
     * Two independent clocks, and conflating them is the likeliest way to get this
     * module wrong. This one decides WHETHER a line may be paid; {@code PayoutCadence}'s
     * fortnight decides WHEN a run happens. A line that matures on day 8 is not paid
     * on day 8 - it waits for the next fortnightly run like everything else.
     *
     * <h2>Corrections carry {@code maturesAt == occurredAt}</h2>
     * Enforced by {@code chk_vendor_ledger_entries_correction_is_immediate}. A
     * refund that had to serve its own hold could arrive after the payout of the
     * sale it reverses, which is precisely the failure the hold exists to prevent.
     */
    @Column(name = "matures_at", nullable = false, updatable = false)
    private OffsetDateTime maturesAt;

    /** Shown verbatim on the statement - the only human explanation a vendor gets for a row. */
    @Column(name = "memo", updatable = false, length = 500)
    private String memo;

    /**
     * A deterministic string derived from what this row is FOR, so posting the same
     * event twice collides on {@code uq_vendor_ledger_entries_idempotency_key}
     * instead of quietly doubling a vendor's balance.
     *
     * <p>Every trigger for a ledger write is repeatable - a delivery confirmation
     * can race the escrow sweep, a refund can be clicked twice, a payout run can be
     * retried after a timeout that actually committed. Services check first and
     * return quietly on an already-posted event; a genuine simultaneous race
     * reaches the index and fails loudly, which is the same posture
     * {@code CompanyVendorLinkService} and {@code OrderNumberAllocator} take.
     * See {@code VendorLedgerKeys} for the format.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * The human behind the row, when there was one: a super admin for a reversal or
     * a payout, null for the automatic accrual - which has no human, and inventing
     * one would be a false audit record.
     *
     * <p>Deliberately not a foreign key: the actor can be a {@code super_admins}
     * row or a {@code users} row depending on the path, and a column that can point
     * at either table cannot be constrained to one of them.
     */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;
}
