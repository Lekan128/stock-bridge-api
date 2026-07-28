package com.procurepal_services.stock_bridge_api.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for {@link PaymentReconciliationService}.
 *
 * <h2>Why this is its own conditional config and not an annotation on the app class</h2>
 * {@code @EnableScheduling} on {@code StockBridgeApiApplication} would start a
 * scheduler in every Spring context in the test suite, with no way to switch it
 * off short of editing the application class. Here, one property disables both the
 * scheduler and the sweeps:
 *
 * <pre>app.monnify.reconciliation.enabled: false</pre>
 *
 * <p>Note the belt and braces: this condition removes the scheduler entirely, and
 * {@link PaymentReconciliationService#scheduledSweep()} re-checks the same flag.
 * The second check is what lets a test flip the property without rebuilding the
 * context, and costs nothing.
 *
 * <h2>Why it is safe to leave on by default in tests</h2>
 * Existing tests run with no Monnify credentials, and the payment sweep returns
 * immediately when {@code isConfigured()} is false. The abandoned-checkout sweep
 * only touches orders older than 24 hours and does nothing at all while no
 * OrderPaymentApplication bean is registered. Neither can perturb an assertion;
 * the flag exists so a test that cares can still be deterministic.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.monnify.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentSchedulingConfig {
}
