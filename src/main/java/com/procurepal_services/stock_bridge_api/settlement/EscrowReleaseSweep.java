package com.procurepal_services.stock_bridge_api.settlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer behind the unconfirmed-delivery rule, and nothing else.
 *
 * <p>All the reasoning - why fourteen days, why the order stays DELIVERED, why the
 * accrual is dated to when escrow matured rather than to when the sweep ran - lives
 * on {@link VendorLedgerService}. This class exists only so that the schedule is
 * separable from the behaviour: the work is a public method a test can call
 * directly, and this is the one thing that would otherwise make it untestable.
 * Same split, for the same reason, as
 * {@code PaymentReconciliationService.scheduledSweep} and its two public sweeps.
 *
 * <h2>Still OFF by default, and M9 made that alignment explicit</h2>
 * The owner's M9 instruction begins "money stays in escrow until the buyer
 * confirms". This sweep is the one mechanism in the application that would release
 * escrow WITHOUT a buyer confirming, and it has defaulted to disabled since M7 - so
 * today's default behaviour already is, literally, what the owner asked for. M9
 * therefore changes nothing here except to say so: the default stays off, and the
 * mechanism is deliberately NOT deleted, because "if a buyer never confirms, pay
 * the vendor anyway after N days" is a rule the owner may well want later and
 * rebuilding it would be strictly worse than leaving it tested and one property
 * away.
 *
 * <p><b>The operational consequence, stated so nobody discovers it:</b> with this
 * off, a buyer who never presses "confirm receipt" strands their vendor's money
 * indefinitely. Nothing expires it, nothing escalates it, and the vendor's statement
 * shows it as "in escrow" with no date attached, forever. That WILL generate support
 * load - a vendor who shipped in good faith, chasing a payment with no scheduled
 * date - and the only levers today are chasing the buyer or an operator calling
 * {@code POST /api/superadmin/settlement/escrow/release} by hand. M9 does not solve
 * it; see {@link VendorLedgerService}'s class doc, which carries the same warning
 * next to the rule it qualifies.
 *
 * <p>Note that the M9 maturity hold applies on top when this DOES run: a swept
 * release accrues dated to {@code deliveredAt + 14 days} and then matures a further
 * {@code escrowHoldDays} after that, because the sweep asserts a confirmation and
 * the hold runs from confirmation however it was arrived at.
 *
 * <h2>Hourly, not nightly</h2>
 * The condition being swept is "has been DELIVERED for fourteen days", so the
 * interval only decides how stale the release can be, and an hour keeps the
 * worst-case lag under a hundredth of the window it is enforcing. Nightly would
 * have been fine too; hourly means an operator who has just enabled the flag sees
 * something happen while they are still looking, which is worth more than the
 * saved queries.
 *
 * <p>{@code initialDelayString} matches the interval so a rolling restart does not
 * fire every instance's sweep simultaneously at boot - the same guard, and the same
 * reasoning, as the payment reconciliation sweep.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscrowReleaseSweep {

    private final VendorLedgerService vendorLedgerService;

    @Scheduled(
            fixedDelayString = "${app.vendor-settlement.escrow-release.interval:PT1H}",
            initialDelayString = "${app.vendor-settlement.escrow-release.interval:PT1H}")
    public void scheduledSweep() {
        if (!vendorLedgerService.isEscrowReleaseEnabled()) {
            return;
        }
        try {
            vendorLedgerService.releaseUnconfirmedDeliveries();
        } catch (RuntimeException ex) {
            // Swallowed and logged rather than propagated, because an exception out of
            // a @Scheduled method silently cancels nothing but this run - and the next
            // run will retry the same orders anyway, since the accrual it failed to
            // write is exactly what the candidate query filters on. Left unlogged it
            // would be invisible.
            log.error("Escrow release sweep failed; the next run will retry the same orders", ex);
        }
    }
}
