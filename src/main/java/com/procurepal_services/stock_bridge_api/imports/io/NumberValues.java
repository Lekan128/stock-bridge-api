package com.procurepal_services.stock_bridge_api.imports.io;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns what somebody typed into a price or quantity cell into a number, or says it cannot.
 *
 * <h2>Why this is more than {@code new BigDecimal(text)}</h2>
 * A cell in a properly formatted column arrives as a clean stored number and none of this runs.
 * The cases that need rescuing are the ones where it did not:
 * <ul>
 *   <li>a CSV exported from another system, where every value is text and the currency symbol
 *       came along with it ({@code "N45,000.00"}, {@code "₦45,000"});</li>
 *   <li>a column somebody re-typed after formatting it as text, so {@code "45,000"} is genuinely
 *       the string with the comma in it;</li>
 *   <li>accounting-style negatives, {@code "(500)"}, which every finance package writes and no
 *       number parser accepts;</li>
 *   <li>European grouping, {@code "1.500,00"}, from an ERP configured for a European locale -
 *       common enough on migration files to be worth the twenty lines.</li>
 * </ul>
 * Each of those, unhandled, is an error row on a value the user got right. That is the whole
 * argument: this class exists so the review screen has fewer rows on it.
 *
 * <h2>The one genuinely ambiguous case, and the call made</h2>
 * A single comma followed by exactly three digits - {@code "1,500"} - is 1500 under en-NG,
 * en-GB and en-US grouping, and 1.5 under de-DE decimal convention. It is read as
 * <b>grouping</b>: this product's market writes {@code 1,500} for one thousand five hundred, and
 * a European file that reaches this branch would have had to contain a bare {@code 1,500} with no
 * other separator anywhere to be misread. Getting this wrong in the other direction - reading
 * every Nigerian user's {@code 45,000} as forty-five - would be a far more frequent and far more
 * damaging error, and it would be silent.
 */
public final class NumberValues {

    /** Currency marks and stray letters that ride along on a value copied out of another system. */
    private static final Pattern CURRENCY_AND_SPACING =
            Pattern.compile("(?i)[\\s\\u00A0]|₦|NGN|USD|GBP|EUR|[$£€]");

    /**
     * A bare {@code N} in front of the digits - by far the most common way the Naira is written
     * when the ₦ glyph is not to hand, which on a Windows keyboard is most of the time. Handled
     * separately from the currency set above because it must only be stripped when a digit follows
     * it: a lone {@code N} elsewhere is a letter, not a currency.
     */
    private static final Pattern LEADING_NAIRA_LETTER = Pattern.compile("^[Nn](?=[0-9.,])");

    private static final Pattern GROUPED_BY_COMMA = Pattern.compile("^\\d{1,3}(,\\d{3})+$");

    private static final Pattern GROUPED_BY_DOT = Pattern.compile("^\\d{1,3}(\\.\\d{3})+$");

    private NumberValues() {
    }

    /**
     * The parsed value, or empty when the text is not a number at all. Empty is the caller's cue
     * to raise "must be a number" against the column it came from - this class deliberately does
     * not know which column that is, or what the message should say.
     */
    public static Optional<BigDecimal> parseDecimal(String raw) {
        String text = CellValues.clean(raw);
        if (text == null) {
            return Optional.empty();
        }
        text = CURRENCY_AND_SPACING.matcher(text).replaceAll("");
        text = LEADING_NAIRA_LETTER.matcher(text).replaceAll("");
        if (text.isEmpty()) {
            return Optional.empty();
        }

        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true;
            text = text.substring(1, text.length() - 1);
        }
        if (text.startsWith("+")) {
            text = text.substring(1);
        } else if (text.startsWith("-")) {
            negative = !negative;
            text = text.substring(1);
        }

        String normalized = normalizeSeparators(text);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            return Optional.of(negative ? value.negate() : value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Reduces whatever mix of dots and commas the value carries to a single {@code .} decimal
     * point, per the rules in the class javadoc.
     */
    private static String normalizeSeparators(String text) {
        int lastComma = text.lastIndexOf(',');
        int lastDot = text.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            // Both present: whichever comes last is the decimal point, the other is grouping.
            // "1.500,00" and "1,500.00" both reduce to "1500.00" with no ambiguity at all.
            char decimalSeparator = lastComma > lastDot ? ',' : '.';
            char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
            return text.replace(String.valueOf(groupingSeparator), "").replace(decimalSeparator, '.');
        }
        if (lastComma >= 0) {
            return GROUPED_BY_COMMA.matcher(text).matches() ? text.replace(",", "") : text.replace(',', '.');
        }
        if (GROUPED_BY_DOT.matcher(text).matches()) {
            // "1.500.000" - more than one dot in a grouping pattern can only be grouping, since a
            // number has at most one decimal point.
            return text.replace(".", "");
        }
        return text;
    }
}
