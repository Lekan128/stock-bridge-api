package com.procurepal_services.stock_bridge_api.entity;

/**
 * How often {@link ProductSkuSettings#getNextSequence()} rolls back to 1.
 *
 * <p>Stored independently of whether {@link ProductSkuSettings#getPattern()} actually contains a
 * date token ({@code {YYYY}}/{@code {YY}}/{@code {MM}}/{@code {DD}}) - the Advanced pattern editor
 * always exposes this control. Without a date token in the pattern, a cadence other than {@link
 * #NEVER} is simply inert: there is no rendered period for the counter to be "within", so
 * SkuGenerationService never observes a period change and the counter behaves as if {@link
 * #NEVER} had been chosen.
 */
public enum SkuResetCadence {

    /** The counter only ever goes up. */
    NEVER,

    /** Resets to 1 the first time a reservation is made in a new calendar year. */
    YEARLY,

    /** Resets to 1 the first time a reservation is made in a new calendar month. */
    MONTHLY
}
