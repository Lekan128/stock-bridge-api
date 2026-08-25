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
    PIECE("PIECE", "Piece", UnitOfMeasureCategory.COUNT, UnitOfMeasureRole.BASE),
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
    MILLIGRAM("MG", "Milligram (mg)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE),
    GRAM("G", "Gram (g)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE),
    KILOGRAM("KG", "Kilogram (kg)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE),
    METRIC_TON("T", "Metric Ton (t)", UnitOfMeasureCategory.WEIGHT, UnitOfMeasureRole.BASE),

    // =========================================================== VOLUME ====
    MILLILITER("ML", "Milliliter (ml)", UnitOfMeasureCategory.VOLUME, UnitOfMeasureRole.BASE),
    LITER("LITER", "Liter (L)", UnitOfMeasureCategory.VOLUME, UnitOfMeasureRole.BASE),

    // =========================================================== LENGTH ====
    MILLIMETER("MM", "Millimeter (mm)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE),
    CENTIMETER("CM", "Centimeter (cm)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE),
    METER("M", "Meter (m)", UnitOfMeasureCategory.LENGTH, UnitOfMeasureRole.BASE);

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

    private static final Map<String, UnitOfMeasure> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(UnitOfMeasure::code, Function.identity()));

    UnitOfMeasure(String code, String label, UnitOfMeasureCategory category, UnitOfMeasureRole role) {
        this.code = code;
        this.label = label;
        this.category = category;
        this.role = role;
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

    private static List<UnitOfMeasure> byRole(UnitOfMeasureRole role) {
        return Arrays.stream(values()).filter(unit -> unit.role == role).toList();
    }
}
