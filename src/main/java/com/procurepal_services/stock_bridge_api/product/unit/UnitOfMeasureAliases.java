package com.procurepal_services.stock_bridge_api.product.unit;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The forgiving half of unit resolution: every spelling of a unit a real person actually types
 * into a spreadsheet cell, mapped to the one {@link UnitOfMeasure} constant it means.
 *
 * <h2>Why this exists at all</h2>
 * BULK_IMPORT_DESIGN.md section 5.2 makes the argument in three steps, and all three matter:
 * the template's dropdowns are an <em>affordance</em>, not a contract (Google Sheets and Numbers
 * degrade or drop xlsx data validation entirely, and pasting into a validated cell bypasses it
 * even in Excel itself); therefore a meaningful share of uploads will contain typed rather than
 * picked values; therefore the parser has to be generous on the way in or it will manufacture
 * error rows for files that were never actually wrong. An error row that never gets created is
 * strictly better than a good screen for repairing it - this class is where a large share of
 * that gets won, invisibly.
 *
 * <h2>Why it is a separate class from the enum</h2>
 * {@link UnitOfMeasure} is the closed, backend-owned catalog and its {@link UnitOfMeasure#fromCode}
 * is the strict gate the API contract depends on - a submitted value either IS one of the fixed
 * codes or it is not. That strictness must not be loosened, so the fuzzy table lives beside it
 * rather than inside it, and {@link UnitOfMeasure#fromCodeOrLabel} is the separate, explicitly
 * forgiving entry point spreadsheet parsing calls. A caller reading {@code fromCode} at its call
 * site still sees exactly the guarantee it always had.
 *
 * <h2>The Nigerian B2B trade vocabulary is deliberate</h2>
 * The aliases below are not a generic English pluralization exercise. They are the words used on
 * waybills, invoices and stock sheets in the market this product serves: a {@code KEG} of
 * groundnut oil (which everyone calls a jerrycan, and some spreadsheets call a gallon), a
 * {@code CRATE} of minerals, a {@code CTN} of noodles, {@code BGS} of rice, {@code MT} of cement.
 * Each one that resolves here is a row a user never has to come back and fix.
 *
 * <h2>How lookup works</h2>
 * Three layers, cheapest first, all against the same {@link #normalize} form (invisible
 * characters stripped, punctuation dropped, whitespace collapsed, uppercased):
 * <ol>
 *   <li>the constant's own {@link UnitOfMeasure#code}, e.g. {@code KG};</li>
 *   <li>forms derived automatically from its {@link UnitOfMeasure#label} - the whole label
 *       ({@code KILOGRAM (KG)}), the label with its parenthetical removed ({@code KILOGRAM}),
 *       and the parenthetical's own contents ({@code KG}) - so the display string the frontend
 *       picker and the units endpoint both hand out is always accepted back verbatim, without
 *       anyone having to remember to add it here;</li>
 *   <li>the hand-written aliases in {@link #ALIASES}.</li>
 * </ol>
 * A trailing {@code S} is stripped and retried on a miss, which is why the table lists
 * {@code BAG} but not {@code BAGS}, {@code PC} but not {@code PCS}, {@code KILO} but not
 * {@code KILOS}. Listing both forms would double the table for no added coverage and is exactly
 * the kind of duplication that goes stale on one side.
 *
 * <h2>Collisions are a build-time failure, not a silent last-write-wins</h2>
 * Two units claiming the same alias would mean one of them silently stopped resolving - the
 * worst possible failure for a table whose whole job is to be right quietly. {@link #register}
 * therefore throws on a conflicting mapping, which surfaces the moment the class is first
 * touched (i.e. in the unit tests) rather than as a mysterious wrong unit on a customer's import
 * months later.
 */
final class UnitOfMeasureAliases {

    /**
     * Zero-width and non-breaking space characters spreadsheet apps commonly leave behind - the
     * same set {@code ProductExcelService} has always stripped from cell values, repeated here
     * because this class is also reachable from callers that never went through a cell (the API,
     * a CSV field, a value-mapping the user typed into the review screen).
     */
    private static final Pattern INVISIBLE_CHARACTERS =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00A0]");

    /**
     * Punctuation that JOINS two words and therefore has to become a space, not vanish:
     * {@code "jerry-can"} must land on the same key as {@code "jerry can"}, and
     * {@code "metric/ton"} on the same key as {@code "metric ton"}. Both dash variants Excel's
     * autocorrect produces are included, because an en dash typed into a cell is invisible to
     * the person who typed a hyphen.
     */
    private static final Pattern JOINING_PUNCTUATION = Pattern.compile("[-\\u2010-\\u2015/\\\\_]");

    /**
     * Punctuation that is pure noise and is deleted outright, so {@code "kg."}, {@code "K.G"}
     * and a smart-quoted {@code "kg’s"} all collapse onto {@code KG}. Deleted rather than
     * space-substituted for exactly the reason the joining set above is not: a period inside a
     * unit abbreviation separates nothing, it is just how some people write abbreviations.
     */
    private static final Pattern NOISE_PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N} ]");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * The hand-written half of the table, singular forms only (see the class javadoc on the
     * trailing-S retry). Grouped by constant, with a comment wherever the mapping is a judgement
     * rather than an obvious synonym.
     */
    private static final Map<UnitOfMeasure, List<String>> ALIASES = Map.ofEntries(
            // ------------------------------------------------------------------ COUNT ----
            // "NO"/"NOS" ("number of") is the standard quantity abbreviation on Nigerian and
            // Indian-authored procurement sheets, and "EA"/"EACH" is the ERP export spelling
            // (SAP, NetSuite, Sage all emit one of them) - both mean "an uncounted discrete
            // item", which is exactly what PIECE is for.
            Map.entry(UnitOfMeasure.PIECE, List.of("PC", "PIECE", "UNIT", "EACH", "EA", "ITEM", "NO", "NOS")),
            Map.entry(UnitOfMeasure.PACK, List.of("PACK", "PKT", "PACKET", "PK", "SACHET")),
            Map.entry(UnitOfMeasure.DOZEN, List.of("DOZEN", "DOZ", "DZ", "DZN")),
            Map.entry(UnitOfMeasure.BOX, List.of("BOX", "BOXE", "BX")),
            Map.entry(UnitOfMeasure.CARTON, List.of("CARTON", "CTN", "CARTN", "CRTN")),
            Map.entry(UnitOfMeasure.CASE, List.of("CASE", "CS")),
            // A crate of minerals/eggs - one of the most common trade units here, and "CRT" is
            // how it is abbreviated on a waybill.
            Map.entry(UnitOfMeasure.CRATE, List.of("CRATE", "CRT")),
            Map.entry(UnitOfMeasure.BAG, List.of("BAG", "BG")),
            Map.entry(UnitOfMeasure.SACK, List.of("SACK", "SCK")),
            Map.entry(UnitOfMeasure.BALE, List.of("BALE", "BLE")),
            Map.entry(UnitOfMeasure.BUNDLE, List.of("BUNDLE", "BDL", "BND")),
            Map.entry(UnitOfMeasure.DRUM, List.of("DRUM", "DRM", "BARREL", "BBL")),
            // "Jerrycan" and (locally) "gallon" both name the plastic container cooking oil,
            // kerosene and diesel are sold in - a container, not a volume. Mapping "GALLON" to a
            // container is a judgement, and the role filter is what makes it safe: KEG is
            // PACKAGING-role, so "gallon" can only ever resolve in a packaging_unit column. In a
            // BASE unit column it still comes back empty and the user is asked, which is the
            // right answer for a word that means a measure elsewhere in the world.
            Map.entry(UnitOfMeasure.KEG, List.of("KEG", "JERRYCAN", "JERRY CAN", "GALLON", "GAL")),
            Map.entry(UnitOfMeasure.PALLET, List.of("PALLET", "PLT", "PALET")),
            Map.entry(UnitOfMeasure.SET, List.of("SET")),
            Map.entry(UnitOfMeasure.PAIR, List.of("PAIR", "PR")),
            Map.entry(UnitOfMeasure.ROLL, List.of("ROLL", "RL", "REEL")),
            Map.entry(UnitOfMeasure.TRAY, List.of("TRAY", "TRY")),
            Map.entry(UnitOfMeasure.BASKET, List.of("BASKET", "BSK", "BASKT")),

            // ----------------------------------------------------------------- WEIGHT ----
            Map.entry(UnitOfMeasure.MILLIGRAM, List.of("MG", "MILLIGRAM", "MILLIGRAMME", "MILLIGRAMS")),
            Map.entry(UnitOfMeasure.GRAM, List.of("G", "GM", "GR", "GRAM", "GRAMME")),
            // "KILO" on its own always means kilogram in trade; nobody sells a kilometre of rice.
            Map.entry(
                    UnitOfMeasure.KILOGRAM,
                    List.of("KG", "KGM", "KILO", "KILOGRAM", "KILOGRAMME", "KILO GRAM", "KILOGRAMS")),
            // "MT" (metric tonne) is how cement, fertiliser and grain are quoted here, and it is
            // also the UN/CEFACT code - by far the most common of these in practice.
            Map.entry(UnitOfMeasure.METRIC_TON, List.of("T", "MT", "TON", "TONNE", "METRIC TON", "METRIC TONNE", "TONS")),

            // ----------------------------------------------------------------- VOLUME ----
            Map.entry(UnitOfMeasure.MILLILITER, List.of("ML", "MILLILITER", "MILLILITRE", "MILS")),
            Map.entry(
                    UnitOfMeasure.LITER,
                    List.of("L", "LT", "LTR", "LITER", "LITRE", "LTRS", "LITERS", "LITRES")),

            // ----------------------------------------------------------------- LENGTH ----
            Map.entry(UnitOfMeasure.MILLIMETER, List.of("MM", "MILLIMETER", "MILLIMETRE")),
            Map.entry(UnitOfMeasure.CENTIMETER, List.of("CM", "CENTIMETER", "CENTIMETRE")),
            Map.entry(UnitOfMeasure.METER, List.of("M", "MTR", "METER", "METRE", "MTRS")));

    /**
     * Built once, eagerly, so a collision fails at class-initialization time. Keys are
     * {@link #normalize}d; values are the single constant that spelling means.
     */
    private static final Map<String, UnitOfMeasure> BY_ALIAS = buildTable();

    private UnitOfMeasureAliases() {
    }

    /**
     * The forgiving lookup behind {@link UnitOfMeasure#fromCodeOrLabel}. Blank input is "not
     * provided" and comes back empty, matching {@link UnitOfMeasure#fromCode}'s own treatment of
     * blank rather than making every caller pre-check.
     */
    static Optional<UnitOfMeasure> resolve(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = normalize(raw);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        UnitOfMeasure direct = BY_ALIAS.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        // The trailing-S retry - see the class javadoc. Guarded on length so a bare "S" (or a
        // one-letter code that happens to end in S) can never degrade into an empty key.
        if (key.length() > 2 && key.endsWith("S")) {
            return Optional.ofNullable(BY_ALIAS.get(key.substring(0, key.length() - 1)));
        }
        return Optional.empty();
    }

    /**
     * Uppercased, punctuation-free, whitespace-collapsed form of a candidate value. Public to the
     * package so the table build and the lookup can never disagree about what a key looks like -
     * a normalizer used on only one side of a map is a bug waiting to happen.
     */
    static String normalize(String raw) {
        String withoutInvisibles = INVISIBLE_CHARACTERS.matcher(raw).replaceAll(" ");
        String spaced = JOINING_PUNCTUATION.matcher(withoutInvisibles).replaceAll(" ");
        String withoutPunctuation = NOISE_PUNCTUATION.matcher(spaced).replaceAll("");
        return WHITESPACE_RUN
                .matcher(withoutPunctuation.trim())
                .replaceAll(" ")
                .toUpperCase(Locale.ROOT);
    }

    private static Map<String, UnitOfMeasure> buildTable() {
        Map<String, UnitOfMeasure> table = new HashMap<>();
        for (UnitOfMeasure unit : UnitOfMeasure.values()) {
            register(table, unit.code(), unit);
            for (String derived : labelForms(unit.label())) {
                register(table, derived, unit);
            }
        }
        ALIASES.forEach((unit, aliases) -> aliases.forEach(alias -> register(table, alias, unit)));
        return Map.copyOf(table);
    }

    /**
     * The three spellings a display label yields: the whole thing, the part before any
     * parenthetical, and the parenthetical's own contents. {@code "Kilogram (kg)"} gives
     * {@code KILOGRAM KG}, {@code KILOGRAM} and {@code KG}; {@code "Piece"} gives just
     * {@code PIECE}. Deriving these rather than listing them is what stops the table drifting the
     * next time a label is reworded.
     */
    private static List<String> labelForms(String label) {
        int open = label.indexOf('(');
        int close = label.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return List.of(label);
        }
        return List.of(label, label.substring(0, open), label.substring(open + 1, close));
    }

    private static void register(Map<String, UnitOfMeasure> table, String spelling, UnitOfMeasure unit) {
        String key = normalize(spelling);
        if (key.isEmpty()) {
            return;
        }
        UnitOfMeasure existing = table.putIfAbsent(key, unit);
        if (existing != null && existing != unit) {
            throw new IllegalStateException("Unit alias '" + key + "' is claimed by both " + existing + " and " + unit
                    + " - one of them would silently stop resolving. Pick a different alias.");
        }
    }
}
