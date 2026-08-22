package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Where a vendor's money actually is, right now - the answer to "what is still in
 * escrow versus payable now", which is the half of the statement a vendor cares
 * about most and the half a plain list of ledger rows cannot show.
 *
 * <h2>FOUR states since M9, and only three of them are in the ledger</h2>
 * <ul>
 *   <li><b>Pending</b> ({@link #pendingNet}) - the buyer has paid, the goods are not
 *       yet confirmed delivered. There is NO ledger entry for this and there should
 *       not be: the platform is holding the buyer's money and the vendor has not
 *       earned it. Derived from the orders themselves. This is the "still at risk"
 *       figure - it can go to zero through a cancellation rather than through a
 *       payment.</li>
 *   <li><b>Maturing</b> ({@link #maturing}) - <em>new in M9.</em> The buyer HAS
 *       confirmed, so the vendor has earned it and it is a real ledger balance, but
 *       the {@link #escrowHoldDays}-day hold has not expired so no payout run may
 *       claim it yet. This is the bucket the whole module was changed to create, and
 *       folding it into either neighbour would be the mistake: it is not "in escrow"
 *       (the buyer confirmed) and it is not "payable" (the hold is running).
 *       {@link #maturingTranches} says exactly when each slice of it ripens.</li>
 *   <li><b>Held</b> ({@link #heldBalance}) - accrued and not yet discharged by a
 *       transfer. This IS the ledger balance and it is the same number as the
 *       statement's closing balance. It now splits three ways:
 *       {@link #maturing} + {@link #payableNow} + {@link #inFlight}.</li>
 *   <li><b>Settled</b> - paid, and therefore no longer a balance at all. It shows up
 *       as the {@code payouts} movement rather than as a position.</li>
 * </ul>
 *
 * <h2>The identity, and the one case where it visibly bends</h2>
 * {@link #heldBalance} equals {@link #maturing} plus {@link #payableNow} plus
 * {@link #inFlight} in the ordinary case. It can differ, legitimately, if a
 * correction is posted against a line a PENDING batch has already claimed - the
 * correction is unclaimed and lands in payableNow while the original stays in
 * flight. The fields are reported separately rather than derived from one another
 * for exactly that reason.
 *
 * <p><b>{@link #payableNow} can be NEGATIVE while nothing is wrong.</b> A refund
 * inside the hold window posts an immediately-mature reversal (a correction never
 * waits - see {@code EscrowHoldPolicy}) against a still-immature sale, so for the
 * rest of the hold the vendor carries a negative payable and an equal positive
 * maturing, netting to the zero they are actually owed. That is the SAFE direction:
 * a run's net goes down, never up, so no money leaves for a sale that came back.
 * The figure is reported honestly rather than clamped, and the vendor's screen
 * explains it.
 *
 * @param pendingProceeds gross value of sold-and-paid-for goods not yet confirmed
 *     delivered.
 * @param pendingProjectedCommission what the platform WOULD charge on them, computed
 *     by the same rounding rule that will actually be applied. A projection: nothing
 *     in the ledger corresponds to it, and a cancellation makes it never happen.
 * @param pendingNet the two netted - what the vendor would receive if every pending
 *     order completes.
 * @param pendingOrderCount how many orders are behind the pending figure, so a vendor
 *     can tell one large shipment from forty small ones.
 * @param heldBalance what the platform owes right now. Identical to the statement's
 *     closing balance; carried here too because this record is also served on its own.
 *     Can be NEGATIVE after a refund that outran a payout - see the clawback rule on
 *     {@code VendorLedgerService.reverseAccrual}.
 * @param maturing the part of {@code heldBalance} that is earned but still inside its
 *     hold. Nothing can pay this until it ripens, whatever the cadence does.
 * @param payableNow the part of {@code heldBalance} that has matured and which no
 *     batch has claimed - what the next run will pick up. Note this is measured
 *     against NOW, whereas a run is measured against the fortnight CUTOFF, so a
 *     tranche ripening between now and the cutoff is counted here and will indeed be
 *     in that run. A tranche ripening after the cutoff is in {@link #maturing} and
 *     will not be.
 * @param inFlight the part a PENDING batch has claimed but no transfer has yet
 *     discharged. Already scheduled, not yet paid.
 * @param escrowHoldDays the hold CURRENTLY in force, in days. Carried so the screen
 *     can explain the maturing bucket without hard-coding a number that a super admin
 *     can change. It describes what NEW confirmations will get - money already
 *     accrued keeps the hold it was accrued under, because
 *     {@code vendor_ledger_entries.matures_at} is stamped once on an append-only row.
 *     So this number will occasionally not explain an existing tranche's date, and
 *     that is correct rather than a bug.
 * @param nextMaturityAt when the soonest tranche ripens, or null when nothing is
 *     maturing. A convenience over {@link #maturingTranches} so a client can render
 *     the headline without walking the list.
 * @param maturingTranches every slice of the maturing money with its own date and
 *     the run that will carry it, soonest first. Empty when nothing is maturing.
 * @param nextPayoutCutoff when the next biweekly cutoff falls, so "payable now" has a
 *     date attached instead of being an open-ended promise. See {@code PayoutCadence}.
 *     Unchanged by M9: the cadence and the hold are two independent clocks.
 */
public record VendorEscrowPosition(
        BigDecimal pendingProceeds,
        BigDecimal pendingProjectedCommission,
        BigDecimal pendingNet,
        int pendingOrderCount,
        BigDecimal heldBalance,
        BigDecimal maturing,
        BigDecimal payableNow,
        BigDecimal inFlight,
        int escrowHoldDays,
        OffsetDateTime nextMaturityAt,
        List<MaturingTranche> maturingTranches,
        OffsetDateTime nextPayoutCutoff) {
}
