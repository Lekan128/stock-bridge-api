package com.procurepal_services.stock_bridge_api.imports;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for {@link ImportExpirySweep}.
 *
 * <h2>Why its own config rather than reusing an existing one</h2>
 * The same reasoning {@code VendorSettlementSchedulingConfig} spells out: the other classes
 * carrying {@code @EnableScheduling} are conditional on properties that belong to entirely
 * different features, so switching Monnify reconciliation or escrow release off would silently
 * take the import sweep with it. Two unrelated jobs sharing one off switch is the kind of
 * coupling nobody notices until the day it matters, and {@code @EnableScheduling} is idempotent
 * across configurations, so the fix costs one class.
 *
 * <p>Unconditional, unlike that one, because this job deletes only abandoned work in progress
 * that was already promised a two-day life. {@link ImportExpirySweep} still re-reads its own
 * property, so an operator can switch it off without rebuilding the context - and
 * {@code ImportSessionService.collectExpired} is public so a test can drive it directly rather
 * than waiting an hour for a tick.
 */
@Configuration
@EnableScheduling
public class ImportSchedulingConfig {
}
