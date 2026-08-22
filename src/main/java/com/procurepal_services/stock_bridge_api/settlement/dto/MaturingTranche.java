package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One slice of a vendor's still-maturing money: how much ripens, exactly when, and
 * which payout run will therefore carry it.
 *
 * <h2>Why this exists rather than a single "maturing" total</h2>
 * Because the sentence that actually stops a support email has three parts, and a
 * total only has one. "₦48,200 is maturing" invites the reply "when?"; <b>"₦48,200
 * becomes payable on 3 March and will be paid on the 10th"</b> does not. Both
 * halves of that need a per-date breakdown, because a vendor with a fortnight of
 * deliveries has several dates and quoting only the earliest would be a promise the
 * later money does not keep.
 *
 * <p>There is deliberately no batch on a tranche, and the absence is the point
 * rather than an omission: nothing here has been claimed by a run yet. A tranche
 * with a batch would be {@code VendorEscrowPosition.inFlight} instead, which is the
 * next bucket along.
 *
 * <h2>Both dates, because neither implies the other</h2>
 * {@link #maturesAt} is the hold expiring - the moment the entry becomes eligible.
 * {@link #payableOnRunAfter} is the first fortnightly CUTOFF at or after that,
 * which is when a run would actually claim it. They are days or weeks apart and
 * they are the two clocks this module keeps deliberately separate: maturity decides
 * whether, cadence decides when. Reporting only the first would have a vendor
 * expecting money on the day it ripens; reporting only the second would hide why it
 * is not in the run that is about to happen.
 *
 * @param maturesAt the exact instant the hold expires. Grouped on the instant and
 *     not on the calendar day, because two confirmations on the same day mature at
 *     different times of day and rounding them together here would assert a date
 *     the ledger cannot support. The screen does the day-level presentation, where
 *     being a few hours out is cosmetic rather than a money error.
 * @param amount the net signed sum of the entries maturing at that instant - the
 *     proceeds and their commission together, so it is what the vendor actually
 *     receives rather than a gross figure they then have to net down.
 * @param payableOnRunAfter the first payout cutoff at or after {@link #maturesAt}.
 *     A prediction rather than a commitment - the run is an operator action (see
 *     {@code VendorSettlementService}), so a late run pays this money later. The
 *     AMOUNT it will pay is not affected by lateness, which is the property
 *     {@code PayoutCadence} exists to guarantee.
 */
public record MaturingTranche(OffsetDateTime maturesAt, BigDecimal amount, OffsetDateTime payableOnRunAfter) {
}
