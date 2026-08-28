package com.procurepal_services.stock_bridge_api.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * The sentences. BULK_IMPORT_DESIGN.md section 9.6's copy principles, applied in one place so
 * both import kinds read alike.
 *
 * <h2>Why copy is server-side at all</h2>
 * Contract section 4 puts it plainly: the backend composes {@code text} and the frontend renders
 * it verbatim. The reason is not tidiness. Three of the twelve non-negotiables in section 8 are
 * copy rules - no column name as an error subject, no UUID, the count inside every bulk
 * affordance - and a rule enforced in a React component is enforced by whoever edits that
 * component next. Here they are enforced by the only code that can produce these strings, and a
 * test can assert on the output.
 *
 * <p>Two consequences worth naming. Numbers are grouped ({@code 3,400}, not {@code 3400})
 * because that is how the count reads in the mock and how a person writes it. And the words
 * "escrow", "session", "staging" and "batch" appear nowhere below - section 8.6 forbids them in
 * the UI, and this class is the UI's vocabulary.
 */
public final class ImportCopy {

    private ImportCopy() {
    }

    public static String count(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    public static String count(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal trimmed = value.stripTrailingZeros();
        if (trimmed.scale() <= 0) {
            return count(trimmed.longValue());
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMaximumFractionDigits(2);
        return format.format(trimmed);
    }

    /** "1 row" / "42 rows" - the count is always present, never "some". */
    public static String plural(long value, String singular, String pluralForm) {
        return count(value) + " " + (value == 1 ? singular : pluralForm);
    }

    /**
     * "12 new products", "1 new product" - a count, an adjective, and the right noun.
     *
     * <p>Exists because the natural-looking {@code count(n) + " " + adjective + " " + products(n)}
     * produces "12 products new": {@link #products} already carries the count, so composing the
     * two puts the number in the wrong place. The mocks in design 9.4 are unambiguous about the
     * order and this is the method that keeps to it.
     */
    public static String qualified(long value, String adjective, String singular, String pluralForm) {
        return count(value) + " " + adjective + " " + (value == 1 ? singular : pluralForm);
    }

    public static String rows(long value) {
        return plural(value, "row", "rows");
    }

    public static String products(long value) {
        return plural(value, "product", "products");
    }

    public static String suppliers(long value) {
        return plural(value, "supplier", "suppliers");
    }

    public static String deliveries(long value) {
        return plural(value, "delivery", "deliveries");
    }

    public static String money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return "₦" + format.format(amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    /**
     * "12 January", or "12 January - 3 February" for a range. Used by the stock-in confirm line,
     * where the dates are the whole point: a user backdating last month's purchases has to see
     * that we understood them as last month's.
     */
    public static String dateRange(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null) {
            return null;
        }
        java.time.format.DateTimeFormatter format =
                java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);
        String start = from.format(format);
        if (to == null || to.equals(from)) {
            return start;
        }
        return start + " – " + to.format(format);
    }

    /**
     * The short form a person writes after a number: "kg", "L", "piece".
     *
     * <p>Design 9.4's mock reads "3,400 kg", not "3,400 Kilogram (kg)" and certainly not
     * "3,400 KG" - nobody writes a unit in capitals mid-sentence. Most of our labels already
     * carry the symbol in parentheses, so that is what is pulled out; the count-category ones
     * ("Piece", "Bag") have no parenthetical and their lower-cased label is exactly right.
     */
    public static String unitSymbol(String code) {
        String label = unitLabel(code);
        if (label == null) {
            return null;
        }
        int open = label.indexOf('(');
        int close = label.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            return label.substring(open + 1, close);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    /**
     * "Kilogram (kg)" for a unit code, falling back to the code when it is not one of ours.
     * Design 9.6 again: {@code KG} is our vocabulary, not the reader's.
     */
    public static String unitLabel(String code) {
        if (code == null) {
            return null;
        }
        return com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure.fromCode(code)
                .map(com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure::label)
                .orElse(code);
    }

    /**
     * Joins a short list the way a person would - "KG or BAG", "A, B or C" - for messages that
     * name the valid options. Never renders as a bracketed array, which is what a naive
     * {@code String.join} on a set produces and which reads as a debug dump.
     */
    public static String orList(List<String> values) {
        List<String> present = values.stream().filter(java.util.Objects::nonNull).toList();
        if (present.isEmpty()) {
            return "";
        }
        if (present.size() == 1) {
            return present.get(0);
        }
        return String.join(", ", present.subList(0, present.size() - 1)) + " or " + present.get(present.size() - 1);
    }

    /** Quotes a value back at the user the way they typed it. */
    public static String quote(String value) {
        return "“" + value + "”";
    }

    /**
     * The subject of an error sentence: the product this row is about, by name, falling back to
     * its SKU and then to "this row". Section 8.7 forbids naming the column instead, and this is
     * the method that makes obeying it the easy path - {@code "Every product needs a unit of
     * measure - what is Garri 25kg measured in?"} rather than {@code "unit_of_measure is
     * required"}.
     */
    public static String subject(String name, String sku) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (sku != null && !sku.isBlank()) {
            return sku;
        }
        return "this row";
    }
}
