package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A batch's claim on one ledger entry. Two columns and a timestamp, and it exists
 * for a reason worth stating.
 *
 * <h2>Why this is a table and not a column on the ledger</h2>
 * The obvious design is {@code vendor_ledger_entries.settled_batch_id}, set when a
 * batch picks a line up. That requires UPDATEing a ledger row, and
 * {@link VendorLedgerEntry} does not permit that at any layer. Membership is not a
 * financial fact about the entry - it is a claim a batch makes on it - so it lives
 * on its own and the entry is never touched.
 *
 * <h2>Why deleting these is legitimate when deleting a ledger row never is</h2>
 * Marking a batch FAILED deletes its membership rows, releasing the lines back
 * into the next run. Nothing in the ledger changes, because nothing about the money
 * changed: no transfer happened, so no {@code PAYOUT} entry was ever posted. This
 * is the only deletion anywhere in the settlement module.
 *
 * <h2>The guarantee it carries</h2>
 * {@code uq_vendor_payout_batch_lines_ledger_entry} makes it structurally
 * impossible for one ledger entry to be claimed by two live batches. The
 * per-period unique index on the batch table stops a whole run being repeated;
 * this one stops a single line being paid twice by two runs that looked at
 * overlapping windows. Both are needed and neither substitutes for the other.
 */
@Entity
@Table(name = "vendor_payout_batch_lines")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorPayoutBatchLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payout_batch_id", nullable = false, updatable = false)
    private UUID payoutBatchId;

    @Column(name = "ledger_entry_id", nullable = false, updatable = false)
    private UUID ledgerEntryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
