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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One vendor's payable amount for one biweekly period: the document an operator
 * acts on, a vendor is told about, and a bank transfer is reconciled against.
 *
 * <h2>Why a batch is per-vendor and not per-run</h2>
 * A "payout run" pays several vendors, but a vendor is paid by ONE transfer for ONE
 * amount. A run that produced a single row covering six vendors would have nowhere
 * to hang the transfer reference, the paid/failed state or the failure reason,
 * because those are per-vendor facts: one vendor's transfer can bounce on a bad
 * account number while the other five land. So a run creates N of these, and "the
 * run" is simply the set of batches sharing a {@link #periodEnd}.
 *
 * <h2>The cadence</h2>
 * Fixed 14-day windows anchored to Monday 1 January 2024, 00:00 Africa/Lagos. WAT
 * spelled out rather than UTC by default - the business is in Nigeria and a
 * fortnight boundary should be a real Nigerian midnight. {@link PayoutCadence} in
 * the settlement package owns the arithmetic and is the only place it exists.
 *
 * <p>{@link #periodEnd} is the CUTOFF and is exclusive; {@link #periodStart} is
 * descriptive. Eligibility is deliberately "unsettled AND occurred before the
 * cutoff" with no lower bound, so an entry that missed its own fortnight - it was
 * in a batch that failed, or the operator skipped a run - is picked up next time
 * rather than stranded. See V14 for the full argument.
 *
 * <h2>Mutable, unlike the ledger, and only in one direction</h2>
 * This entity has setters and the ledger does not. A batch is a workflow object
 * with a genuine state change in it (a human marks it paid or failed), whereas a
 * ledger row is a historical claim. The change a human makes is recorded IN the
 * ledger as a new {@code PAYOUT} entry, so the audit trail stays append-only even
 * though this row moved.
 *
 * <h2>Frozen totals</h2>
 * {@link #proceedsTotal}, {@link #commissionTotal}, {@link #reversalTotal} and
 * {@link #netAmount} are derivable from the batch's lines and are stored anyway -
 * the one denormalisation in this module. The reason: this is a document somebody
 * is about to transfer money against, and the amount on it must not change if a
 * correction is later posted with a date inside the window. Same argument
 * {@link OrderItem} uses for snapshotting {@code unitPrice}.
 */
@Entity
@Table(name = "vendor_payout_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorPayoutBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "seller_client_id", nullable = false, updatable = false)
    private UUID sellerClientId;

    /** Human-readable and quotable, e.g. {@code PB-2026-000042}. Unique across the platform. */
    @Column(name = "batch_number", nullable = false, updatable = false, length = 40)
    private String batchNumber;

    /** Descriptive: which fortnight this run closes. Not the eligibility predicate - see the class doc. */
    @Column(name = "period_start", nullable = false, updatable = false)
    private OffsetDateTime periodStart;

    /** The exclusive cutoff. Everything unsettled and older than this went into the batch. */
    @Column(name = "period_end", nullable = false, updatable = false)
    private OffsetDateTime periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VendorPayoutBatchStatus status;

    /** Always NGN. The column exists so the single-currency assumption is visible rather than implied. */
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** Sum of the batch's {@code SALE_PROCEEDS} lines. Positive. */
    @Column(name = "proceeds_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal proceedsTotal;

    /** Sum of the batch's {@code COMMISSION} lines. Negative or zero, as the ledger stores it. */
    @Column(name = "commission_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal commissionTotal;

    /** Sum of both reversal kinds. Usually negative on balance, but a commission-only reversal is positive. */
    @Column(name = "reversal_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal reversalTotal;

    /**
     * What gets transferred. Strictly positive, enforced by
     * {@code chk_vendor_payout_batches_net_positive}: there is no such thing as an
     * instruction to transfer nothing, and a vendor whose reversals outweigh their
     * new sales gets no batch at all - the debt nets off against their next sales.
     */
    @Column(name = "net_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    /** The super admin who ran it. Null only if that operator's account was later deleted. */
    @Column(name = "run_by", updatable = false)
    private UUID runBy;

    @Column(name = "run_at", nullable = false, updatable = false)
    private OffsetDateTime runAt;

    /**
     * Who recorded making the transfer, when, and against what bank reference.
     * There is no gateway callback to corroborate any of this, so the human and the
     * reference ARE the evidence that a vendor was paid.
     */
    @Column(name = "settled_by")
    private UUID settledBy;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    /** Required on FAILED. See {@link VendorPayoutBatchStatus#FAILED}. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
