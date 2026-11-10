package com.procurepal_services.stock_bridge_api.product.unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Stock quantities, now that they hold a fraction (UNITS_PLAN.md Part 0, V81): "5 dericas" of rice is 3 kg, and
 * 7 is 4.2 kg. Every figure the ledger keeps is a {@link BigDecimal} of the product's stock unit at
 * {@link #SCALE} places - to the gram for kg, the millilitre for litres. Pieces stay whole; that rule is the
 * service's ({@link UnitOptions#isCountedInWholeUnits}), not the number's.
 *
 * <p>Two habits this class exists to make easy, because a {@code BigDecimal} punishes forgetting them:
 * compare with {@link #same} or {@code compareTo} (never {@code equals}: {@code 3.000} is not {@code 3}), and
 * read a database number with {@link #of(Object)} (never {@code intValue()}, which drops the fraction without a
 * word).
 */
public final class Quantities {

    /** Places a stock quantity is kept to: the scale of every quantity column since V81. */
    public static final int SCALE = 3;

    private Quantities() {
    }

    /** A whole number of the stock unit. */
    public static BigDecimal of(long whole) {
        return BigDecimal.valueOf(whole);
    }

    /**
     * A number read from the database - a {@code numeric}, a {@code bigint} sum, or null for none - as a quantity.
     * Zero for null, so a sum over no rows reads as nothing held.
     */
    public static BigDecimal of(Object number) {
        if (number == null) {
            return BigDecimal.ZERO;
        }
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof Long || number instanceof Integer || number instanceof Short) {
            return BigDecimal.valueOf(((Number) number).longValue());
        }
        return new BigDecimal(number.toString());
    }

    /** {@code value} at the ledger's scale, half up: what the column will hold. */
    public static BigDecimal kept(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** {@code value} with no trailing zeros and never in exponent form: {@code 3}, {@code 4.2}. */
    public static BigDecimal plain(BigDecimal value) {
        return Decimals.plain(value);
    }

    /** Equal as amounts, whatever the scale: {@code 3.000} and {@code 3} are the same. */
    public static boolean same(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    /** True for a whole number: {@code 3.000}, not {@code 2.5}. */
    public static boolean isWhole(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value.signum() == 0;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** True when {@code a} is more than {@code b}. */
    public static boolean exceeds(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
