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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * How long a vendor's confirmed money waits before it may be paid out - the one
 * settlement rule the platform owner can change at runtime (M9).
 *
 * <p>Modelled on {@link MarketplaceSettings} down to the {@code singleton} column
 * and its unique index, because this application already has a shape for
 * "commercial rules ops changes without a redeploy" and consistency beats novelty.
 * Exactly one row exists and the database enforces it.
 *
 * <h2>Why this is a second table rather than a column on MarketplaceSettings</h2>
 * They are gated by different principals, and the difference is the whole point.
 * Every column on {@link MarketplaceSettings} is writable by a platform-owner
 * TENANT admin through {@code PUT /api/marketplace/admin/settings}, with no
 * re-authentication at all. This one is writable only by a SUPER ADMIN who
 * re-enters their password and explicitly acknowledges the consequence. Sharing a
 * row would put one UPDATE path under two authorisation stories, and the weaker
 * one would be a single added request field away from moving money.
 *
 * <h2>Why a table and not a Spring property</h2>
 * A property needs a deploy to change, cannot record who changed it, and has
 * nowhere to hang the audit trail the first payment dispute will need - and the
 * owner asked specifically for a UI-driven, re-authenticated change. There is
 * deliberately no property that can override this row either: two sources of truth
 * for a money rule is how they come to disagree.
 *
 * <h2>THE RULE, because it is the first thing anyone asks</h2>
 * <b>Changing this value affects FUTURE accruals only.</b> The hold is stamped onto
 * {@link VendorLedgerEntry#getMaturesAt()} at accrual, and that table is
 * append-only, so money already confirmed keeps the terms it was confirmed under.
 * See {@code VendorSettlementSettingsService} for the full argument and
 * {@code V15__escrow_maturity_and_settlement_settings.sql} for why the alternative
 * (computing maturity from the current setting at query time) is the wrong answer.
 *
 * <h2>Mutable, unlike the ledger</h2>
 * This class HAS a setter, which every other entity in the settlement package
 * deliberately does not. That is correct here and worth stating so the asymmetry
 * does not read as an oversight: this row holds the CURRENT policy, not a record of
 * anything that happened. The history lives in
 * {@link VendorSettlementSettingsChange}, which is append-only and carries the same
 * trigger the ledger does.
 */
@Entity
@Table(name = "vendor_settlement_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorSettlementSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Always true; it exists only to carry the unique index that makes this a single-row table. */
    @Column(nullable = false)
    private boolean singleton;

    /**
     * Days between the buyer confirming receipt and the money becoming
     * payout-eligible. Bounded {@code [0, 90]} by the request DTO AND by a CHECK -
     * see {@code EscrowHoldPolicy} for both bounds and why zero is allowed.
     */
    @Column(name = "escrow_hold_days", nullable = false)
    private int escrowHoldDays;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
