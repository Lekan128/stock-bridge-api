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

/**
 * One change to the escrow hold: from what, to what, by whom, when and why.
 *
 * <h2>Why this exists when the settings row already holds the value</h2>
 * {@link VendorSettlementSettings} is one row that is UPDATEd, so it has no memory.
 * This is the memory, and it is the half that matters the first time a vendor and
 * the platform disagree about when something should have been paid: answering
 * "what was the hold on 3 March" needs a row that says so. This is money policy,
 * and the first dispute needs it.
 *
 * <h2>Append-only, by the ledger's own trigger</h2>
 * {@code trg_vendor_settlement_settings_changes_append_only} runs the very same
 * {@code vendor_ledger_entries_append_only()} function V14 wrote, rather than a
 * twin of it. An audit trail that can be edited is not an audit trail; reusing the
 * function means there is ONE escape hatch
 * ({@code SET LOCAL app.ledger_maintenance = 'on'}) covering every money-history
 * table in the schema, so a reader who has learned the ledger's rule already knows
 * this one. There are no setters here for the same reason there are none on
 * {@link VendorLedgerEntry}, and every column is {@code updatable = false} so
 * Hibernate cannot decide otherwise.
 *
 * <h2>Both sides of the change on one row</h2>
 * {@link #previousHoldDays} is stored rather than inferred from the previous row.
 * Reconstructing history by walking a table in date order is how off-by-one answers
 * get given under oath, and one line of this table should be a complete statement
 * without joining to its neighbours.
 *
 * <h2>Why the username is snapshotted next to the id</h2>
 * {@code changed_by} is {@code ON DELETE SET NULL}, matching
 * {@code vendor_payout_batches.run_by}: an operator leaving the company must not
 * delete the record of the decisions they made. Which is exactly why the id alone
 * is not enough - once it is nulled, a change would have no human on it at all, and
 * "somebody changed how money moves" is not an audit record. Same argument
 * {@code order_items.product_name} makes for snapshotting a name onto a sold line.
 *
 * <h2>One row per real change</h2>
 * A request that sets the hold to the value it already has writes nothing here (and
 * sends no email): it changed nothing, and an audit trail padded with no-ops is one
 * nobody reads. {@code chk_vendor_settlement_settings_changes_actually_changed}
 * makes that structural.
 */
@Entity
@Table(name = "vendor_settlement_settings_changes")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorSettlementSettingsChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "previous_hold_days", nullable = false, updatable = false)
    private int previousHoldDays;

    @Column(name = "new_hold_days", nullable = false, updatable = false)
    private int newHoldDays;

    /**
     * The super admin who made the change. Nullable only because the FK is
     * {@code ON DELETE SET NULL} - it is never written null.
     */
    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    /** Snapshotted, so the record survives the account being deleted. See the class doc. */
    @Column(name = "changed_by_username", nullable = false, updatable = false, length = 255)
    private String changedByUsername;

    /**
     * What the operator said, when they said anything. Optional on purpose: a
     * mandatory reason field on a form gets "asdf" typed into it, which is worse
     * than an honest blank.
     */
    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    /**
     * Written by the service rather than defaulted by the database, so the audit row
     * and the email that announces it quote the same instant.
     */
    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;
}
