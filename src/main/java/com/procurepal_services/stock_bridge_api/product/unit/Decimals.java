package com.procurepal_services.stock_bridge_api.product.unit;

import java.math.BigDecimal;

/**
 * One rule about {@link BigDecimal}, in one place, because it has now caught us three times.
 *
 * <p>{@code stripTrailingZeros()} is the obvious way to turn {@code 42000.0000} into {@code 42000},
 * and on a round number it does something else entirely: it produces a negative scale, so
 * {@code new BigDecimal("42000.0000").stripTrailingZeros()} is {@code 4.2E+4}. Jackson writes that
 * to the wire literally, and {@code toString} prints it in a log, a label or an error message.
 *
 * <p>JavaScript parses {@code 4.2E+4} back to 42000, so this never breaks a screen - which is
 * exactly why it survives review. It surfaces later, in the places nobody is looking at while they
 * write the code: an exported CSV, a webhook payload, a support log, a stricter client. A price
 * that reads {@code 4.2E+4} is not a price anybody recognises.
 *
 * <p>{@code PackContents} and {@code UnitOfMeasure} each carry their own private copy of this,
 * written the same way for the same reason. They are left alone rather than churned; anything new
 * should call this.
 */
public final class Decimals {

    private Decimals() {
    }

    /** Trailing zeros off, never into exponent form. The value is unchanged; only its scale moves. */
    public static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
