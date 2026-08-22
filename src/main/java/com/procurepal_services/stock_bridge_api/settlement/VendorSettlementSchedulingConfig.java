package com.procurepal_services.stock_bridge_api.settlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for {@link EscrowReleaseSweep}.
 *
 * <h2>Why its own config rather than reusing PaymentSchedulingConfig</h2>
 * That class already carries {@code @EnableScheduling} and would have made this
 * one unnecessary - but it is itself conditional on
 * {@code app.monnify.reconciliation.enabled}, so switching Monnify reconciliation
 * off would silently take the escrow sweep with it. Two unrelated jobs sharing one
 * off switch is the kind of coupling nobody notices until the day it matters, and
 * {@code @EnableScheduling} is idempotent across configurations, so the fix costs
 * one class.
 *
 * <h2>Why this one defaults to OFF and the payment one defaults to ON</h2>
 * Deliberate asymmetry. The reconciliation sweep only ever moves an order that
 * Monnify has already been paid for into the state it should have been in
 * anyway - it is a repair job, and it is dangerous NOT to run it. This one WRITES
 * MONEY ROWS, and the correct default for that is "an operator turned it on".
 *
 * <p>It costs nothing in coverage: {@link VendorLedgerService#releaseUnconfirmedDeliveries()}
 * is public and every test drives it directly, which is the same arrangement
 * {@code PaymentReconciliationService} uses so that its sweeps can be tested
 * deterministically rather than waited for.
 *
 * <p>M9 revisited that default and kept it. The owner's rule - "money stays in
 * escrow until the buyer confirms" - is satisfied exactly by leaving this off, so
 * the default is now aligned with a stated business requirement rather than only
 * with a general caution about jobs that write money rows. The mechanism stays
 * available for the day the owner decides an unconfirmed delivery should eventually
 * pay; see {@link EscrowReleaseSweep} for what leaving it off costs in support load.
 *
 * <pre>app.vendor-settlement.escrow-release.enabled: true</pre>
 *
 * <p>Belt and braces, matching PaymentSchedulingConfig: this condition removes the
 * scheduler entirely, and {@link EscrowReleaseSweep#scheduledSweep()} re-checks the
 * same flag so a test can flip the property without rebuilding the context.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.vendor-settlement.escrow-release",
        name = "enabled",
        havingValue = "true")
public class VendorSettlementSchedulingConfig {
}
