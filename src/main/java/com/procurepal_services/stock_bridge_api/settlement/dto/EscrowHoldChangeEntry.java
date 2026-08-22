package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of the escrow hold's change history: from what, to what, by whom, when
 * and why.
 *
 * <p>Every field is a copy of the audit row rather than a join, including
 * {@link #changedByUsername} - see {@code VendorSettlementSettingsChange} for why the
 * name is snapshotted next to the id. A history line has to keep meaning something
 * after the operator who made the change has left.
 *
 * @param changedBy the super admin's id, or null once that account has been deleted.
 *     {@link #changedByUsername} is never null, which is the point of carrying both.
 * @param reason what the operator said, or null if they said nothing.
 */
public record EscrowHoldChangeEntry(
        UUID id,
        int previousHoldDays,
        int newHoldDays,
        UUID changedBy,
        String changedByUsername,
        String reason,
        OffsetDateTime changedAt) {
}
