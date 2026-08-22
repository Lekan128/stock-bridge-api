package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the next run would produce, for every vendor, at the cadence's current
 * cutoff.
 *
 * <p>{@link #cutoff} is computed from the CADENCE and not from when this was
 * requested, which is the property that makes an operator-triggered run
 * reproducible: previewing on Tuesday shows exactly what running on Monday night
 * would have produced. See {@code PayoutCadence}.
 *
 * @param cutoff the exclusive cutoff a run made now would use.
 * @param periodStart the fortnight this run closes. Descriptive - eligibility has no
 *     lower bound, so entries older than this that an earlier run missed are
 *     included too.
 * @param nextCutoff when the following period closes, so the screen can say when the
 *     next run is due rather than leaving an operator to count fortnights.
 * @param alreadyRun sellers that already have a live batch for this cutoff. Their
 *     rows are still listed, marked ineligible, so "I ran this an hour ago" is
 *     visible instead of looking like the vendor earned nothing.
 * @param totalNet the sum of every ELIGIBLE vendor's net - what the run would cost
 *     in total. The single number an operator checks against the bank before
 *     pressing anything.
 */
public record PayoutRunPreview(
        OffsetDateTime cutoff,
        OffsetDateTime periodStart,
        OffsetDateTime nextCutoff,
        OffsetDateTime generatedAt,
        int alreadyRun,
        BigDecimal totalNet,
        List<EligibleVendorPayout> vendors) {
}
