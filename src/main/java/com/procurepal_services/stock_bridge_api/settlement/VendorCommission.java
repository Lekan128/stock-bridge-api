package com.procurepal_services.stock_bridge_api.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The rounding rule, in the one place it is allowed to exist.
 *
 * <h2>The rule</h2>
 * <pre>
 *     commission = ROUND(lineTotal * commissionRate) to 2 decimal places, HALF UP
 * </pre>
 * Applied per ORDER LINE, and the rounded per-line figures are then summed.
 * A total is never re-derived by applying a rate to an order total.
 *
 * <h2>Why half-up to the kobo, and why it is stated at all</h2>
 * Commission is the only figure in this module that is computed rather than
 * copied, so it is the only one where two honest implementations can disagree.
 * Half-up to two decimals is the conventional choice for NGN, but the reason that
 * matters here is narrower than convention: it is what a person gets with a
 * calculator. The requirement this module was given is that a vendor must be able
 * to reproduce our arithmetic by hand from their statement, and half-even (Java's
 * silent default in some APIs) would produce a figure a vendor would compute
 * differently roughly half the time it mattered - each disagreement a kobo, each
 * one an email.
 *
 * <h2>Why per line, and why the sum is of rounded figures</h2>
 * The rate is stamped per line ({@code order_items.commission_rate}), so different
 * lines of one order can legitimately carry different rates. Rounding once at the
 * order level would therefore need a blended rate that appears nowhere in the
 * data, and the statement's own line-by-line arithmetic would stop adding up to
 * its own total. Sum-of-rounded can differ from round-of-sum by a kobo or two;
 * that difference is accepted deliberately, because the alternative is a statement
 * whose columns do not add up, which is the failure a vendor actually notices.
 *
 * <h2>Why this is a class and not a static helper on the service</h2>
 * Because "applied in exactly one place" has to be checkable. Two callers post
 * commission - the accrual and the reversal - and a third computes the projected
 * fee on escrow that has not accrued yet. All three call this, and a grep for
 * {@code setScale} anywhere else in the settlement package should return nothing.
 */
public final class VendorCommission {

    /** NGN has two decimal places (kobo). Not configurable: multi-currency is deliberately out of scope. */
    public static final int MONEY_SCALE = 2;

    /** See the class doc. Named rather than inlined so the choice is greppable. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private VendorCommission() {
    }

    /**
     * The platform's fee on one line, as a positive magnitude. The caller decides
     * nothing about the sign - {@code VendorLedgerEntryType.COMMISSION.normalise(...)}
     * does that.
     *
     * @param lineTotal what the buyer paid for this line. Must not be null.
     * @param commissionRate the rate frozen on the line at sale time, as a fraction
     *     ({@code 0.0750} is 7.5%). NULL means "no commission applies" - V11 is
     *     explicit that this is a different fact from an agreed zero rate - and
     *     yields null here so the caller can skip posting a row at all rather than
     *     posting a misleading zero.
     * @return the rounded fee, or null when no commission applies.
     */
    public static BigDecimal on(BigDecimal lineTotal, BigDecimal commissionRate) {
        if (commissionRate == null || lineTotal == null) {
            return null;
        }
        return lineTotal.multiply(commissionRate).setScale(MONEY_SCALE, ROUNDING);
    }

    /** A money figure at the module's canonical scale. Used so every response field agrees on 2dp. */
    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
    }

    /** Zero at the canonical scale, so a JSON body never mixes {@code 0} and {@code 0.00}. */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
    }
}
