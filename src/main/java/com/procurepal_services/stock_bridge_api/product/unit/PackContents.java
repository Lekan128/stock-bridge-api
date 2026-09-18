package com.procurepal_services.stock_bridge_api.product.unit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one pack holds, written the way a person writes it on an invoice - {@code "50 kg"},
 * {@code "12 x 750 ml"}, {@code "piece"}.
 *
 * <h2>Why one cell instead of two columns</h2>
 * The catalog sheet used to ask three separate questions: {@code stock_unit}, {@code pack} and
 * {@code units_per_pack}. PACK_ENTRY_REDESIGN.md section 15 cut that to two, because the stock
 * unit was already inside the answer to the third: a user who writes "50 kg" has said the unit is
 * kg, and asking again in its own column is asking the same question twice. That column was also
 * the most abstract cell on the row - the one a person has to translate before they can fill it
 * in - so deleting it removes the hardest question rather than rewording it.
 *
 * <h2>The multiplication is the reported case, finally expressible</h2>
 * {@code "12 x 750 ml"} is a pack of twelve 750 ml bottles: 9,000 ml. The buyer proposed exactly
 * this at the outset ("we should let the user input something like (750*12)") and section 1
 * rejected it, on three grounds. Two of them died with the two-column layout:
 *
 * <ul>
 *   <li><b>"It hides that the stock unit is ml."</b> No longer - the unit is written in the same
 *       cell, and this class returns it.
 *   <li><b>"The two numbers do not survive."</b> No longer - the cell keeps the text it was given,
 *       so a pack can still describe itself as twelve 750s a year later.
 *   <li><b>"12 x 750 and 750 x 12 both parse."</b> Still true, and accepted: both mean 9,000 ml in
 *       one pack, which is the only number the ledger runs on. The order of two factors is not a
 *       fact worth refusing a row over.
 * </ul>
 *
 * <h2>The grammar is deliberately tiny</h2>
 * <pre>
 *   50 kg          -> 50 kg in one pack
 *   12 x 750 ml    -> 9,000 ml in one pack
 *   piece          -> no pack size at all; the product is counted in pieces
 * </pre>
 *
 * A number, an optional {@code x} and second number, and a unit word. Nothing else is accepted,
 * because every shape this does not recognise falls to the review grid's dropdowns - a bad cell
 * costs a click, not a wrong shelf. The unit word is resolved by {@link UnitOfMeasure#fromCodeOrLabel},
 * which already accepts codes, display labels and about eighty trade spellings.
 */
public record PackContents(BigDecimal packSize, UnitOfMeasure unit) {

    /**
     * Every multiplication sign a person or a spreadsheet might produce: the letter x in both
     * cases, the real multiplication sign, the middle dot, and the asterisk that comes from
     * typing what looks like a formula. {@code *} is included because "750*12" is what the
     * buyer wrote when first describing the problem.
     */
    private static final String TIMES = "[x\\u00D7\\u00B7*]";

    /**
     * {@code <number> [<times> <number>] <unit>}. The unit is everything after the last number,
     * so "12 x 750 ml" and "50 kilogrammes" both land - the trailing group is handed to
     * {@code fromCodeOrLabel} rather than matched here, because this pattern has no business
     * knowing how a kilogram may be spelled.
     */
    private static final Pattern SIZED = Pattern.compile(
            "^\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:" + TIMES + "\\s*(\\d+(?:[.,]\\d+)?)\\s*)?(.+?)\\s*$");

    /**
     * Reads one {@code contains} cell.
     *
     * @return empty when the cell is blank or does not match the grammar - both are "ask the user",
     *     never a guess. A caller that needs to tell those apart checks the raw value for blank
     *     itself; this method deliberately does not, so there is one failure shape to handle.
     */
    public static Optional<PackContents> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();

        // A bare unit - "piece", "kg". No pack size, which is a complete and correct answer: the
        // product is bought loose and counted in that unit.
        Optional<UnitOfMeasure> bare = UnitOfMeasure.fromCodeOrLabel(value)
                .filter(unit -> unit.canServeAs(UnitOfMeasureRole.BASE));
        if (bare.isPresent()) {
            return Optional.of(new PackContents(null, bare.get()));
        }

        Matcher matcher = SIZED.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<UnitOfMeasure> unit = UnitOfMeasure.fromCodeOrLabel(matcher.group(3))
                .filter(candidate -> candidate.canServeAs(UnitOfMeasureRole.BASE));
        if (unit.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal first = decimal(matcher.group(1));
        BigDecimal second = matcher.group(2) == null ? null : decimal(matcher.group(2));
        BigDecimal size = second == null ? first : first.multiply(second);
        if (size.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PackContents(normalize(size), unit.get()));
    }

    /**
     * The inverse, for the export and the template: {@code (50, KG)} becomes {@code "50 kg"}.
     *
     * <p>Deliberately never writes the {@code 12 x 750} form, even for a pack whose size happens
     * to factor neatly. The factors are not stored - only their product is - so reconstructing
     * them would be inventing a fact about the user's packaging. A round trip therefore reads
     * "9,000 ml", which is true, rather than "12 x 750 ml", which might not be.
     */
    public static String format(BigDecimal packSize, String unitCode) {
        String symbol = UnitOfMeasure.fromCode(unitCode).map(UnitOfMeasure::symbol).orElse(unitCode);
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        if (packSize == null) {
            return symbol;
        }
        return normalize(packSize).toPlainString() + " " + symbol;
    }

    /** Commas are decimal separators in half the world and thousands separators in the other. */
    private static BigDecimal decimal(String group) {
        return new BigDecimal(group.replace(',', '.'));
    }

    /**
     * Trailing zeros off, but never into exponent form: {@code stripTrailingZeros()} turns 50 into
     * {@code 5E+1}, whose {@code toString} is what a label or an error message would print.
     * Clamping the scale at zero keeps the value identical and the spelling human.
     */
    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
