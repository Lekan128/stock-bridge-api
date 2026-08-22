package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The escrow hold as an operator screen needs it: the value, the bounds it may move
 * within, when it last moved, and what a change would and would not do.
 *
 * <h2>Why the bounds and the retroactivity note are in the payload</h2>
 * Both are policy the SERVER owns, and a screen that hard-coded either would drift
 * the day somebody widened {@code EscrowHoldPolicy}. More importantly,
 * {@link #appliesToFutureAccrualsOnly} is the sentence the operator most needs to
 * read before pressing the button, and the one they are most likely to assume the
 * opposite of - so it is carried verbatim rather than left for a frontend to phrase.
 * Same reasoning {@code VendorStatementResponse.salesProceedsBasis} gives for
 * carrying its own note.
 *
 * @param escrowHoldDays the hold currently in force. Applies to accruals from now on;
 *     see below.
 * @param minHoldDays zero, and it is a legal value - it means "payable the moment the
 *     buyer confirms", which is the behaviour that shipped before this feature. The
 *     screen should offer it rather than treat it as an error.
 * @param maxHoldDays ninety. A bound on damage, not a target.
 * @param payoutPeriodDays the payout cadence, fourteen, carried so the screen can
 *     warn that a hold longer than a fortnight systematically pushes every vendor a
 *     cycle later. It is NOT changeable and is here only so the two numbers can be
 *     compared in one place - the cadence is a constant precisely because every batch
 *     ever created names its own period.
 * @param appliesToFutureAccrualsOnly always true, and stated as a field rather than
 *     assumed, because it is the first question an operator asks and the one an
 *     honest screen has to answer above the submit button. A change stamps nothing
 *     onto money that has already accrued: {@code vendor_ledger_entries.matures_at}
 *     is written once, on an append-only row, so a sale keeps the hold that was in
 *     force when its buyer confirmed it.
 * @param updatedAt when the settings row last changed. Equals {@code createdAt} until
 *     somebody changes it for the first time.
 * @param lastChange the most recent audit row, or null if the hold has never been
 *     changed from the value the migration seeded. Repeated inside
 *     {@link #recentChanges} - carried separately so a screen can show "who last
 *     touched this" without rendering a table.
 * @param recentChanges newest first, capped by the service. The full history is not
 *     paged over an endpoint because this table gains a row only when a human changes
 *     a money rule, which is a handful of times a year.
 */
public record EscrowHoldSettingsResponse(
        int escrowHoldDays,
        int minHoldDays,
        int maxHoldDays,
        int payoutPeriodDays,
        boolean appliesToFutureAccrualsOnly,
        OffsetDateTime updatedAt,
        EscrowHoldChangeEntry lastChange,
        List<EscrowHoldChangeEntry> recentChanges) {
}
