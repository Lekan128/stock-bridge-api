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

    /**
     * Joins the way {@link #orList} does but with "and" - "9,500 kg and 600 pieces". Used where
     * the list is a set of facts that are ALL true at once rather than a set of alternatives.
     *
     * <p>It exists because a preview line that stated a mixed-unit total as one number would be
     * the exact defect UNIT_UX_CONTRACT.md section 6.3 forbids ("a bare sum of mixed entered
     * quantities must not appear anywhere"), and "3,400 units" across kilograms and cartons is
     * that sum wearing a different noun.
     */
    public static String andList(List<String> values) {
        List<String> present = values.stream().filter(java.util.Objects::nonNull).toList();
        if (present.isEmpty()) {
            return "";
        }
        if (present.size() == 1) {
            return present.get(0);
        }
        return String.join(", ", present.subList(0, present.size() - 1)) + " and " + present.get(present.size() - 1);
    }

    /**
     * A total stated in ledger terms - "9,500 kg", or "9,500 kg and 600 pieces" when the file
     * covers products counted in more than one stock unit. UNIT_UX_CONTRACT.md section 6.3.
     *
     * <h2>Why this cannot collapse to a single number</h2>
     * Every quantity in the map has already been converted into its own product's stock unit, so
     * each entry is honest on its own. What is not available is a way to add kilograms to pieces:
     * they are different categories, and UNIT_UX_CONTRACT.md section 2.2 is explicit that
     * cross-category conversion "is not a conversion and must never be offered". The old code's
     * answer to that was to print the raw sum followed by the word "units", which is a number the
     * ledger never records - P0-4 exactly. Stating each unit's own total is longer and true.
     *
     * @param totalsByStockUnitSymbol stock-unit symbol ("kg", "pieces") to the total counted in
     *     it. Insertion-ordered by the caller so the biggest contributor reads first.
     */
    public static String quantityTotals(java.util.Map<String, Long> totalsByStockUnitSymbol) {
        return andList(totalsByStockUnitSymbol.entrySet().stream()
                .map(entry -> count(entry.getValue()) + " " + entry.getKey())
                .toList());
    }

    /**
     * The "(190 bags)" half of section 6.3's sentence - what the user actually typed, restated
     * beside what the ledger will record.
     *
     * <p>The grammar is not decided here. {@code UnitOptions.spokenPhrase} already owns the two
     * rules that matter (a parenthesised symbol never pluralises, a word always does), and this
     * method only narrows its answer for a pack: "bags of 50 kg" becomes "bags", because the
     * "of 50 kg" is the conversion evidence and the conversion is already stated in full on the
     * other side of the parenthesis. Re-deriving the pluralisation here instead of trimming M1's
     * answer would be a second copy of the one rule this remediation exists to keep single.
     *
     * @param enteredQuantity the number as typed, counted in {@code option}.
     * @param option which member of the product's unit set that number was in.
     */
    public static String enteredQuantityPhrase(
            long enteredQuantity, com.procurepal_services.stock_bridge_api.product.unit.UnitOption option) {
        String phrase = com.procurepal_services.stock_bridge_api.product.unit.UnitOptions.spokenPhrase(option);
        int of = phrase.indexOf(" of ");
        return count(enteredQuantity) + " " + (of > 0 ? phrase.substring(0, of) : phrase);
    }

    /**
     * The line that renders under a quantity cell on the review grid - {@code "= 2,000 kg"},
     * UNIT_UX_CONTRACT.md section 6.2, and non-negotiable 3 applied to the grid: what was typed
     * and what the ledger will record, together, on the same row.
     *
     * @param baseQuantity the quantity converted into the product's stock unit.
     * @param stockUnitCode the product's {@code unitOfMeasure}; rendered as its short symbol, and
     *     falling back to "units" for the pre-V17 product that never got one - the same word
     *     {@code UnitOptions} uses for that product's single placeholder option, so the grid and
     *     the picker say the same thing.
     */
    public static String baseQuantityText(long baseQuantity, String stockUnitCode) {
        String symbol = com.procurepal_services.stock_bridge_api.product.unit.UnitOptions.symbolOf(stockUnitCode);
        return "= " + count(baseQuantity) + " "
                + (symbol.isEmpty()
                        ? com.procurepal_services.stock_bridge_api.product.unit.UnitOptions.NO_STOCK_UNIT_LABEL
                        : symbol);
    }

    // ---------------------------------------------------------------- vocabulary

    /**
     * UNIT_UX_CONTRACT.md section 1's locked user-facing names, spelled once.
     *
     * <p>Section 1 puts it plainly: "the strings live in exactly two modules and are imported,
     * never retyped", and this class is the backend half. Four vocabularies for three concepts is
     * what UNIT_UX_REMEDIATION_PLAN.md section 2 traced across six surfaces; a constant is the
     * cheapest thing that makes the fifth vocabulary impossible to add by accident.
     *
     * <p>They are field LABELS, not help text - the one string a column is called on every screen
     * that renders it, including the downloadable report, which takes its headers straight from
     * these descriptors.
     */
    public static final class Labels {

        private Labels() {
        }

        /** {@code Product.unitOfMeasure}. Never "Measured in", "base unit", "unit of measure". */
        public static final String STOCK_UNIT = "Stock unit";

        /** {@code packagingUnit} + {@code packagingSize} as one idea. Never "Packaged as". */
        public static final String PACK = "Pack";

        /** {@code packagingSize} alone, when a number must be entered. Never "Pack size". */
        public static final String UNITS_PER_PACK = "Units per pack";

        /** The wire's {@code unit} - which unit a typed number is in. Never "Unit". */
        public static final String COUNTED_IN = "Counted in";

        /** {@code CompanyVendor}, the buyer's own directory. Never "Vendor". */
        public static final String SUPPLIER = "Supplier";

        /** {@code ProductVendor.vendorSku}. Never "Their SKU". */
        public static final String SUPPLIERS_CODE = "Supplier's code";

        /**
         * The catalog's opening balance. UNIT_UX_CONTRACT.md section 9.4's label, plain.
         *
         * <p>It used to read "Opening stock (in stock unit)", and the parenthetical was there on
         * purpose: the same row carries a pack, a user onboarding "20 bags of rice" typed 20 and
         * got 20 kg (P0-3), and stating the basis in the label was the fix. Section 9.1 fixed it
         * the other way instead - the number now counts PACKS whenever the row declares one, so
         * the parenthetical had become false. It is dropped rather than inverted to "(in packs)",
         * because that would be false on every row that has no pack; what the number is counted
         * in is a fact about the row, not about the column, and the row states it.
         */
        public static final String OPENING_STOCK = "Opening stock";

        /**
         * {@code low_stock_alert_at}, renamed from {@code low_stock_threshold} by
         * UNIT_UX_CONTRACT.md section 9.4, which words the label exactly this way.
         *
         * <p>It is a sentence rather than a noun because that is what the column actually is: the
         * user is not describing a threshold, they are telling us when to speak up. Counted the
         * same way {@link #OPENING_STOCK} is - section 9.1, and two quantity columns on one row
         * counting different things is the defect that amendment removes.
         */
        public static final String LOW_STOCK_ALERT_AT = "Tell me when stock falls to";

        /**
         * Non-negotiable 2: no price field is labelled without naming what it is per. Both cost
         * labels below name their basis in the label rather than only in the help text, because
         * the grid renders the label on every screen and the help text only on hover.
         */
        public static final String COST_PER_STOCK_UNIT = "Cost per pack";

        /** Stock-in's {@code cost_per_unit}: per whatever THIS row's "Counted in" says. */
        public static final String COST_PER_COUNTED_IN_UNIT = "Cost per counted-in unit";
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
