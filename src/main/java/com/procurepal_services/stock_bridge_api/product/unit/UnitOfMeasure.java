package com.procurepal_services.stock_bridge_api.product.unit;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fixed, backend-owned list of units of measure a product may reference, split along two
 * independent axes: {@link UnitOfMeasureCategory} (what KIND of quantity - count, weight,
 * volume, length) and {@link UnitOfMeasureRole} (what the code is FOR - see that enum for why
 * the role split exists and the "50kg bag" ambiguity it removes). A product pairs a
 * {@link UnitOfMeasureRole#BASE} code as {@code Product.unitOfMeasure} with an OPTIONAL
 * {@link UnitOfMeasureRole#PACKAGING} code as {@code Product.packagingUnit} plus a numeric
 * {@code Product.packagingSize} - e.g. {@link #KILOGRAM} + {@link #BAG} + 50 means "a 50kg
 * bag"; {@link #LITER} alone means "sold loose by the litre".
 *
 * <h2>This is the single source of truth</h2>
 * Every later consumer - product create/update validation, the bulk-upload spreadsheet
 * importer, and (via the read endpoint on {@code ProductController}) the frontend picker - is
 * expected to validate against and render from THIS list rather than keeping its own copy.
 * {@link #fromCode(String)} is the intended entry point for that validation: look a submitted
 * value up here before accepting it, don't re-derive the list elsewhere. {@code fromCode}
 * deliberately does NOT check {@link #role()} - see that field's javadoc for why the role
 * constraint belongs at the call site instead.
 *
 * <h2>Grouping</h2>
 * Modelled on Amazon/Jumia/Odoo unit pickers: four categories by what KIND of quantity is
 * being measured (see {@link UnitOfMeasureCategory}), not by industry or trade. The list
 * itself is a deliberate, closed set - do not add or remove constants here without checking
 * the migration/entity/product modules that depend on the exact set staying stable, since
 * {@link #code} is a value that may already be persisted (e.g. in a bulk-upload template a
 * tenant has downloaded).
 *
 * <h2>Requests for units not on this list</h2>
 * Deliberately out of scope here. A tenant needing a unit this enum does not have is expected
 * to go through a separate request workflow (a different module); this class only has to not
 * get in that workflow's way, which is why {@link #fromCode(String)} returns an
 * {@link Optional} instead of throwing - "not on the list" is a valid, expected outcome for a
 * caller to branch on, not a defect.
 */
public enum UnitOfMeasure {

    // ============================================================ COUNT ====
    /**
     * The one COUNT-category constant that is {@link UnitOfMeasureRole#BASE} rather than
     * {@link UnitOfMeasureRole#PACKAGING} - reused as the generic "uncounted discrete item"
     * base unit (e.g. "a carton of 24 PIECE") rather than inventing a second constant for the
     * same idea.
     */
    PIECE("PIECE", "Piece", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.BASE, "1"),
    PACK("PACK", "Pack", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    DOZEN("DOZEN", "Dozen", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    BOX("BOX", "Box", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    CARTON("CARTON", "Carton", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    CASE("CASE", "Case", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    CRATE("CRATE", "Crate", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    BAG("BAG", "Bag", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    SACK("SACK", "Sack", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    BALE("BALE", "Bale", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    BUNDLE("BUNDLE", "Bundle", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    DRUM("DRUM", "Drum", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    KEG("KEG", "Keg", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    PALLET("PALLET", "Pallet", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    SET("SET", "Set", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    PAIR("PAIR", "Pair", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    ROLL("ROLL", "Roll", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    TRAY("TRAY", "Tray", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),
    BASKET("BASKET", "Basket", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.PACKAGING),

    // =========================================================== WEIGHT ====
    MILLIGRAM("MG", "Milligram (mg)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE, "0.000001"),
    GRAM("G", "Gram (g)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE, "0.001"),
    KILOGRAM("KG", "Kilogram (kg)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE, "1"),
    METRIC_TON("T", "Metric Ton (t)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE, "1000"),

    // =========================================================== VOLUME ====
    MILLILITER("ML", "Milliliter (ml)", UnitOfMeasureCategory.VOLUME, UnitOfMeasureRole.BASE, "0.001"),
    LITER("LITER", "Liter (L)", UnitOfMeasureCategory.VOLUME, UnitOfMeasureRole.BASE, "1"),

    // =========================================================== LENGTH ====
    MILLIMETER("MM", "Millimeter (mm)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE, "0.001"),
    CENTIMETER("CM", "Centimeter (cm)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE, "0.01"),
    METER("M", "Meter (m)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE, "1");

    /**
     * Short, stable, uppercase identifier - what the bulk-upload spreadsheet column and the
     * API actually carry. Stable deliberately: once a tenant has a spreadsheet or a stored
     * product referencing a code, that code must keep resolving to the same constant for as
     * long as this enum constant exists.
     */
    private final String code;

    /** Human-facing text for a picker, e.g. "Kilogram (kg)". */
    private final String label;

    private final UnitOfMeasureCategory category;

    /**
     * Whether this code may be used as a product's {@code unitOfMeasure}
     * ({@link UnitOfMeasureRole#BASE}) or its {@code packagingUnit}
     * ({@link UnitOfMeasureRole#PACKAGING}) - see that enum's javadoc for the full reasoning.
     * Never both: the two questions "what is it measured in" and "how is it packaged" are
     * always answered by different codes on the same product, which is exactly what removes
     * the "50kg bag" ambiguity the old single-list design had.
     */
    private final UnitOfMeasureRole role;

    /**
     * How many of this category's CANONICAL unit one of this unit is - {@code KG} for WEIGHT,
     * {@code LITER} for VOLUME, {@code M} for LENGTH, {@code PIECE} for COUNT. So
     * {@link #METRIC_TON} is {@code 1000} and {@link #GRAM} is {@code 0.001}. Added by
     * UNIT_UX_CONTRACT.md section 2.2, which pins these exact values.
     *
     * <h2>Null for every PACKAGING-role constant, and that is the whole point</h2>
     * A Bag is not a fixed amount of anything. How much one holds is a fact about a PRODUCT
     * ({@code Product.packagingSize}) or about one supplier's line
     * ({@code ProductVendor.defaultPackagingSize}), never about the word "bag", which is why
     * UNIT_UX_REMEDIATION_PLAN.md section 3 P1-1 records the old "full list of ~30 units" picker
     * as a defect rather than a feature: offering CARTON as an alternative unit for a KG product
     * asks a question that has no answer. A null here is that "no answer", stated once, so
     * {@link #factorTo} can refuse rather than invent.
     *
     * <h2>Meaningful only within one {@link UnitOfMeasureCategory}</h2>
     * Comparing a WEIGHT factor to a VOLUME factor is meaningless - grams to millilitres needs a
     * density, which is a property of the goods, not of the units. {@link #factorTo} enforces the
     * same-category rule so no caller has to remember it.
     */
    private final java.math.BigDecimal factorToCanonical;

    private static final Map<String, UnitOfMeasure> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(UnitOfMeasure::code, Function.identity()));

    /**
     * The PACKAGING-role form: no {@link #factorToCanonical}, because a container word has no
     * fixed size. See that field's javadoc.
     */
    UnitOfMeasure(String code, String label, UnitOfMeasureCategory category, UnitOfMeasureRole role) {
        this(code, label, category, role, null);
    }

    private UnitOfMeasure(
            String code,
            String label,
            UnitOfMeasureCategory category,
            UnitOfMeasureRole role,
            String factorToCanonical) {
        this.code = code;
        this.label = label;
        this.category = category;
        this.role = role;
        this.factorToCanonical = factorToCanonical == null ? null : new java.math.BigDecimal(factorToCanonical);
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public UnitOfMeasureCategory category() {
        return category;
    }

    public UnitOfMeasureRole role() {
        return role;
    }

    /** See the field's javadoc. Null for every PACKAGING-role constant. */
    public java.math.BigDecimal factorToCanonical() {
        return factorToCanonical;
    }

    /**
     * The short form a person writes after a number - {@code "kg"}, {@code "L"}, {@code "Piece"}
     * - pulled out of {@link #label()}'s parenthetical, falling back to the whole label when
     * there is none. UNIT_UX_CONTRACT.md section 2.1 step 1 pins both halves of that rule:
     * {@code "Kilogram (kg)"} becomes {@code "kg"}, {@code "Piece"} stays {@code "Piece"}.
     *
     * <p>Deliberately preserves the fallback's case, unlike {@code ImportCopy.unitSymbol}, which
     * lower-cases it because it is composing the middle of a sentence. This one is composing a
     * {@code UnitOption.label} - the text a picker shows on its own - and a lone lower-case
     * "piece" in a select box reads as a typo. The two are not redundant; they answer the same
     * question for two different positions in the UI.
     */
    public String symbol() {
        int open = label.indexOf('(');
        int close = label.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            return label.substring(open + 1, close);
        }
        return label;
    }

    /**
     * Whether {@link #symbol()} came from a parenthetical abbreviation ({@code kg}, {@code L})
     * rather than from the whole label ({@code Piece}, {@code Bag}). The distinction is a
     * grammar fact a message composer needs: you write "3 kg", never "3 kgs", but you do write
     * "3 pieces" - see {@code UnitOptions.spokenPhrase}.
     */
    public boolean hasSymbolAbbreviation() {
        int open = label.indexOf('(');
        return open >= 0 && label.indexOf(')', open + 1) > open;
    }

    /**
     * How many of {@code stockUnit} one of THIS unit is - the number a quantity entered in this
     * unit is multiplied by to reach the product's stock unit. {@code T.factorTo(KG)} is
     * {@code 1000}; {@code G.factorTo(KG)} is {@code 0.001}. Scale 9, HALF_UP, as
     * UNIT_UX_CONTRACT.md section 2.2 pins.
     *
     * <p>Empty - not an exception, and never a guessed 1 - when either side has no
     * {@link #factorToCanonical} (any PACKAGING constant) or when the two sit in different
     * {@link UnitOfMeasureCategory categories}. Cross-category is not a conversion: kilograms to
     * litres needs a density, which belongs to the goods and not to the units, and section 2.2
     * is explicit that it "must never be offered". An empty result is the signal that this pair
     * simply is not an alternative-unit relationship, which is exactly what a unit-set builder
     * needs to hear in order to leave the unit off the list.
     */
    public Optional<java.math.BigDecimal> factorTo(UnitOfMeasure stockUnit) {
        if (stockUnit == null
                || factorToCanonical == null
                || stockUnit.factorToCanonical == null
                || category != stockUnit.category) {
            return Optional.empty();
        }
        java.math.BigDecimal factor = factorToCanonical
                .divide(stockUnit.factorToCanonical, 9, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        // stripTrailingZeros turns 1000.000000000 into 1E+3, which Jackson writes to the wire
        // verbatim as "1E+3" - a number no spreadsheet cell and no select-box value should ever
        // contain. Pulling a negative scale back to zero restores the plain 1000 without
        // touching the value; 0.001 keeps its scale of 3 and is unaffected.
        return Optional.of(factor.scale() < 0 ? factor.setScale(0) : factor);
    }

    /**
     * Looks up a unit by its {@link #code}, case-insensitively. This is the intended way for
     * another module (product validation, bulk-upload row parsing) to check whether a
     * submitted value is one of the fixed units: call this first, and treat an empty result as
     * "not on the list" rather than an error - that is the exact signal the separate
     * request-a-new-unit workflow exists to handle.
     *
     * <p>Deliberately role-agnostic - see {@link UnitOfMeasureRole}'s javadoc for why a role
     * constraint (e.g. "must be BASE") is the CALLER's job, not this method's. A caller that
     * cares should check {@link #role()} on the result itself.
     *
     * @param code a candidate code, e.g. "kg", "Bag", "PIECE"; blank or null is simply "not
     *     found", not an exception.
     * @return the matching constant, or empty if {@code code} is blank or matches none of the
     *     fixed units.
     */
    public static Optional<UnitOfMeasure> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim().toUpperCase()));
    }

    /**
     * The forgiving sibling of {@link #fromCode}: resolves a code, a display label, a bare label
     * or any of the trade aliases in {@code UnitOfMeasureAliases} - case-insensitively, ignoring
     * punctuation, invisible characters and a trailing plural {@code s}. {@code "KG"},
     * {@code "kg"}, {@code "kgs"}, {@code "Kilogram (kg)"}, {@code "kilogram"}, {@code "kilo"}
     * and {@code "K.G"} all come back as {@link #KILOGRAM}.
     *
     * <h2>Why this is separate from {@link #fromCode} rather than a loosening of it</h2>
     * The two answer genuinely different questions, and conflating them would quietly weaken the
     * API. {@code fromCode} answers "is this one of the fixed codes" - the gate the REST layer,
     * product create/update validation and anything persisting a code must keep using, because
     * accepting {@code "kilo"} from a JSON request body would mean the same product could be
     * described two ways by two integrations. {@code fromCodeOrLabel} answers "what did a human
     * typing into a spreadsheet cell most likely mean", which is a question only the importer
     * has, and only because BULK_IMPORT_DESIGN.md section 5.2 establishes that a spreadsheet's
     * data validation is advisory and a share of cells will always be typed rather than picked.
     *
     * <p>Resolution is still a lookup against this same closed catalog - nothing is invented, and
     * an unrecognized value still comes back empty for the caller to turn into "we don't
     * recognise X, did you mean Y?".
     *
     * @param codeOrLabel any spelling a user might have typed; null or blank is simply "not
     *     provided" and returns empty, exactly as {@link #fromCode} treats it.
     */
    public static Optional<UnitOfMeasure> fromCodeOrLabel(String codeOrLabel) {
        return UnitOfMeasureAliases.resolve(codeOrLabel);
    }

    /**
     * {@link #fromCodeOrLabel} with the role constraint applied, so a BASE column can never
     * resolve to a PACKAGING unit and vice versa.
     *
     * <h2>Why the role belongs in the same call for this entry point</h2>
     * {@link #fromCode} is deliberately role-agnostic (see {@link UnitOfMeasureRole}'s javadoc:
     * its two call sites want different roles, so baking one in would be wrong for the other).
     * The forgiving lookup is different in a way that matters: it exists to serve spreadsheet
     * columns, and a spreadsheet column always knows which role it is - {@code unit_of_measure}
     * is BASE, {@code packaging_unit} is PACKAGING, with no third case. Offering the filter here
     * keeps a caller from writing {@code .filter(u -> u.role() == BASE)} at every call site and
     * eventually forgetting it at one of them, which would let {@code "bags"} land in a product's
     * base unit slot and reintroduce exactly the "50kg bag" ambiguity {@link UnitOfMeasureRole}
     * exists to remove.
     *
     * <p>A value that resolves to a real unit of the WRONG role returns empty, not the unit - the
     * caller cannot tell "not a unit at all" from "not a unit for this column", which is
     * intentional and matches how {@code ProductManagementService.resolveUnitOfMeasure} already
     * reports both cases with one message. Callers wanting to say "BAG is a packaging unit, put
     * it in the packaging_unit column" can ask {@link #fromCodeOrLabel} without the role and
     * compare.
     */
    public static Optional<UnitOfMeasure> fromCodeOrLabel(String codeOrLabel, UnitOfMeasureRole requiredRole) {
        return fromCodeOrLabel(codeOrLabel).filter(unit -> unit.canServeAs(requiredRole));
    }

    /**
     * Whether this unit may play {@code wanted} for some product - the question every role check
     * in the system actually means, replacing {@code role() == wanted}.
     *
     * <h2>Why the declared role is not the answer on its own</h2>
     * {@link #role()} is a property of the CODE. The thing callers need is a property of the
     * USAGE, and for the {@link UnitOfMeasureCategory#COUNT} category the two come apart: "piece"
     * is the stock unit of a phone (there is nothing underneath it) and the pack of a turmeric
     * sold in 34 g pieces ({@code G} + {@code PIECE} + 34). Same code, different job, same
     * catalog. {@code PAIR} and {@code SET} are the same story from the other side - declared
     * PACKAGING, yet a shoe shop's stock unit is plausibly the pair.
     *
     * <p>So COUNT units may serve either role. Everything else stays strict: a kilogram is never
     * a container, and letting {@code packagingUnit=KG} through would resurrect exactly the "50kg
     * bag" ambiguity {@link UnitOfMeasureRole} exists to remove.
     *
     * <h2>What replaces the guarantee this weakens</h2>
     * The strict split was silently guaranteeing that a product's pack differed from its stock
     * unit. Once COUNT units can be both, "Piece of 34 Pieces" becomes expressible, so that
     * invariant is now stated where it belongs - in product validation - rather than falling out
     * of the enum by accident.
     *
     * <h2>On the "modelled on Odoo" claim</h2>
     * {@link UnitOfMeasureRole}'s javadoc says the split follows Odoo. Odoo has no such split:
     * {@code uom.uom} is one flat list per category with conversion ratios (Units and Dozens both
     * sit in the Unit category), and containers are a separate {@code product.packaging} entity.
     * NetSuite likewise keeps Each, Dozen and Case in one Units Type. The role flag is ours, and
     * this method is where it stops being a gate and becomes what it is genuinely good for -
     * grouping and ordering the pickers.
     */
    public boolean canServeAs(UnitOfMeasureRole wanted) {
        if (role == wanted) {
            return true;
        }
        // Deliberately one-directional. A COUNT unit may serve as a PACK even when its declared
        // role is BASE - that is the reported case, "turmeric sold in 34 g pieces". The reverse is
        // NOT opened up here: making BAG a legal stock unit is a wider change than anyone asked
        // for, it widens the stock-unit picker from ten options to twenty-eight, and it is the
        // shape UnitOfMeasureRole's javadoc argues hardest against. If a business that genuinely
        // counts bags and never cares about weight turns up, this is the one line to revisit -
        // and the pack-differs-from-stock-unit invariant is already in place to make it safe.
        return wanted == UnitOfMeasureRole.PACKAGING && category == UnitOfMeasureCategory.COUNT;
    }

    /** Every unit, in declaration order - COUNT, then WEIGHT, then VOLUME, then LENGTH. */
    public static List<UnitOfMeasure> all() {
        return List.of(values());
    }

    /**
     * Every unit whose {@link #role()} is {@link UnitOfMeasureRole#BASE} - the candidates for
     * a product's {@code unitOfMeasure}. Declaration order preserved, same as {@link #all()}.
     */
    public static List<UnitOfMeasure> baseUnits() {
        return byRole(UnitOfMeasureRole.BASE);
    }

    /**
     * Every unit whose {@link #role()} is {@link UnitOfMeasureRole#PACKAGING} - the candidates
     * for a product's {@code packagingUnit}. Declaration order preserved, same as
     * {@link #all()}.
     */
    public static List<UnitOfMeasure> packagingUnits() {
        return byRole(UnitOfMeasureRole.PACKAGING);
    }

    /**
     * Every unit that may serve {@code role} - see {@link #canServeAs}. Declaration order, so the
     * units whose declared role this IS come out grouped by category as before, with the COUNT
     * dual-role ones in their natural place rather than appended.
     */
    private static List<UnitOfMeasure> byRole(UnitOfMeasureRole role) {
        return Arrays.stream(values()).filter(unit -> unit.canServeAs(role)).toList();
    }
}
