package com.procurepal_services.stock_bridge_api.settlement;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * "Biweekly", defined precisely enough to be arithmetic.
 *
 * <h2>The definition</h2>
 * <pre>
 *   Anchor:  Monday 1 January 2024, 00:00:00 Africa/Lagos
 *   Period n: [anchor + 14n days, anchor + 14(n+1) days)   -- half-open
 *   Cutoff:  the START of the period currently in progress
 * </pre>
 * So a run made at any moment during the current fortnight settles everything that
 * happened before that fortnight began. Payout day is therefore every other
 * Monday, and the money in a run is everything confirmed up to 00:00 WAT that
 * Monday.
 *
 * <h2>Africa/Lagos, spelled out, and why not UTC</h2>
 * The business is in Nigeria. WAT is UTC+01:00 and observes no daylight saving, so
 * the offset is constant - but "constant" is not "the same as UTC", and defaulting
 * to UTC would place every cutoff at 01:00 Nigerian time. That is invisible until
 * a delivery confirmed at 00:30 WAT on cutoff day lands in the wrong fortnight and
 * a vendor's statement disagrees with their own records by exactly one order,
 * which is the kind of discrepancy that costs a day to find and all of a vendor's
 * confidence to fix. The zone matters when the instant is CHOSEN, which is what
 * this class does; what gets stored is a {@code TIMESTAMPTZ}, an absolute instant.
 *
 * <h2>Why "the start of the current period" rather than "the end of the last one"</h2>
 * They are the same instant. Stating it as the start of the current period makes
 * the safety property obvious: the cutoff is always in the PAST, so no run can
 * ever settle an entry that has not happened yet, whatever moment an operator
 * happens to click.
 *
 * <h2>Why the anchor is a fixed date in the past rather than configuration</h2>
 * Every batch ever created is a document that names its own period, and moving the
 * anchor would renumber the fortnights under those documents. A constant that
 * cannot be changed by a deployment is the honest shape for something the whole
 * ledger's periodisation depends on. 1 January 2024 was a Monday, which is why
 * payout day is a Monday.
 *
 * <h2>What makes an operator-triggered run reproducible</h2>
 * This is the piece that does it. Because the cutoff is derived from the CADENCE
 * and not from when somebody pressed the button, a run started on Tuesday morning
 * produces exactly the batch a run started on Monday night would have. The only
 * thing lateness changes is when the vendor gets paid, not what they get paid -
 * which is what lets this module ship an operator-triggered endpoint instead of a
 * scheduler it could not test properly. See {@code VendorSettlementService}.
 */
public final class PayoutCadence {

    /** WAT. Not the JVM default, not UTC. See the class doc. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Lagos");

    /** A Monday. See the class doc for why this is a constant. */
    public static final LocalDate ANCHOR_DATE = LocalDate.of(2024, 1, 1);

    /** Biweekly, in the only sense of the word that is arithmetic: every 14 days. */
    public static final int PERIOD_DAYS = 14;

    private PayoutCadence() {
    }

    /** The anchor as an instant, so period arithmetic is done on instants and not on local dates. */
    private static ZonedDateTime anchor() {
        return ANCHOR_DATE.atStartOfDay(ZONE);
    }

    /**
     * The exclusive cutoff for a run made at {@code now}: the start of the fortnight
     * currently in progress. Always strictly in the past, by construction.
     */
    public static OffsetDateTime cutoffFor(OffsetDateTime now) {
        return periodStartContaining(now).toOffsetDateTime();
    }

    /**
     * The start of the fortnight the run CLOSES - i.e. the one immediately before
     * the cutoff. Descriptive only: it names the period on the batch and makes the
     * row readable, and it is deliberately NOT the eligibility predicate. See
     * {@code VendorLedgerEntryRepository.findSettleable} for why eligibility has no
     * lower bound.
     */
    public static OffsetDateTime periodStartFor(OffsetDateTime cutoff) {
        return cutoff.minusDays(PERIOD_DAYS);
    }

    /** When the next run becomes possible - the end of the fortnight in progress. Shown to operators and vendors. */
    public static OffsetDateTime nextCutoffAfter(OffsetDateTime now) {
        return periodStartContaining(now).plusDays(PERIOD_DAYS).toOffsetDateTime();
    }

    /** How long until the next cutoff. Never negative. */
    public static Duration untilNextCutoff(OffsetDateTime now) {
        Duration remaining = Duration.between(now, nextCutoffAfter(now));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * The start of the 14-day period containing {@code moment}, resolved in WAT.
     *
     * <p>Uses whole DAYS between local dates rather than dividing a duration,
     * because a duration-based fortnight index would drift the moment anything in
     * the chain ever observed a daylight-saving transition. Africa/Lagos does not,
     * today - but the arithmetic should not be relying on that, and counting days
     * costs nothing.
     */
    private static ZonedDateTime periodStartContaining(OffsetDateTime moment) {
        ZonedDateTime local = moment.atZoneSameInstant(ZONE);
        long daysSinceAnchor = ChronoUnit.DAYS.between(ANCHOR_DATE, local.toLocalDate());
        long periodIndex = Math.floorDiv(daysSinceAnchor, PERIOD_DAYS);
        return anchor().plusDays(periodIndex * (long) PERIOD_DAYS);
    }
}
