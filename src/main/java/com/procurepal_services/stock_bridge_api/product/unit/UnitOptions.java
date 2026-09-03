package com.procurepal_services.stock_bridge_api.product.unit;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE implementation of UNIT_UX_CONTRACT.md section 2.1 - how a product's closed set of
 * enterable units is derived - plus the two things every caller of that set then needs:
 * resolution of a submitted {@code unit} code against it, and the sentence fragments that name
 * its members to a human.
 *
 * <h2>Why "one implementation" is the whole point of this class</h2>
 * UNIT_UX_REMEDIATION_PLAN.md section 2 traces the same three concepts across six surfaces under
 * four vocabularies, and section 3's P1-1/P1-3 record what happened when two of those surfaces
 * each decided for themselves which units a product accepts: the stock-in modal offered ~30
 * codes, the service accepted two, and picking any of the other twenty-eight was a guaranteed
 * 400. The defect was never in either surface - it was in there being two answers. So this class
 * is the answer, and the stock service, both response DTOs, both spreadsheet writers and the
 * import row handlers all read it rather than each deriving their own.
 *
 * <h2>The set, in order (section 2.1)</h2>
 * Deduplicated by {@code code}, first occurrence wins:
 * <ol>
 *   <li>the product's own stock unit, factor 1;</li>
 *   <li>the product's own pack, when {@code packagingUnit} and {@code packagingSize} are both
 *       set - factor {@code packagingSize}, label "Bag of 50 kg";</li>
 *   <li>one supplier's pack, when a supplier-scoped set was asked for and that supplier's
 *       default pack differs from the product's;</li>
 *   <li>every same-category base unit with a static factor - a KG product also accepts T and G
 *       (section 2.2). Never the default.</li>
 * </ol>
 * {@code isDefault} is the product's own pack if it has one, else the stock unit: a wholesaler
 * who configured "Bag of 50 kg" buys and sells in bags, and preselecting kg would make them
 * change the toggle on every single entry.
 *
 * <h2>What is deliberately NOT in the set</h2>
 * Any unit with no conversion factor - which is every PACKAGING constant that is not THIS
 * product's (or this supplier's) configured pack. Contract non-negotiable 1. A Carton is not an
 * alternative way to count a product whose packs are bags; it is an unanswerable question, and
 * the honest escape hatch is to configure another pack (a persistent act with a reusable
 * result), not to offer a picker entry that 400s.
 */
public final class UnitOptions {

    /**
     * The code carried by the placeholder option of a product with no {@code unitOfMeasure} -
     * an empty string, never null, so a wire consumer can round-trip it as a select value and a
     * {@code unit} of {@code ""} keeps meaning what a {@code unit} of null means. See
     * {@link #forProduct}.
     */
    public static final String NO_STOCK_UNIT_CODE = "";

    /** The label that placeholder shows. Section 2.1: {@code "units"}, lower case. */
    public static final String NO_STOCK_UNIT_LABEL = "units";

    private UnitOptions() {
    }

    /**
     * The product-scoped set - steps 1, 2 and 4, with no supplier pack. What
     * {@code ProductResponse.unitOptions} publishes (contract section 2.3), and what the stock
     * modals drive their toggle from before a supplier has been chosen.
     *
     * <p>Reads only fields already loaded on the entity - no query, no lazy association - so
     * calling it while mapping a page of products costs nothing.
     */
    public static List<UnitOption> forProduct(Product product) {
        return forProduct(product.getUnitOfMeasure(), product.getPackagingUnit(), product.getPackagingSize());
    }

    /**
     * {@link #forProduct(Product)} in terms of the three values it actually needs, for callers
     * that hold them without an entity - a parsed spreadsheet row, an import review row.
     *
     * @param stockUnitCode the product's {@code unitOfMeasure} - the unit EVERY stored quantity
     *     for this product is counted in. Null or blank yields the single-entry "units" set
     *     described by section 2.1's last paragraph: the pre-V17 product that never got a unit.
     * @param packagingUnit the product's own pack container, e.g. {@code "BAG"}. Contributes an
     *     option only together with a positive {@code packagingSize}.
     * @param packagingSize how many stock units one pack holds - the factor of the pack option.
     */
    public static List<UnitOption> forProduct(String stockUnitCode, String packagingUnit, BigDecimal packagingSize) {
        return build(stockUnitCode, packagingUnit, packagingSize, null, null);
    }

    /**
     * The supplier-scoped set - steps 1, 2, 3 and 4. What {@code ProductVendorResponse.unitOptions}
     * publishes (contract section 2.3), and what the stock-in form switches to once a supplier is
     * chosen, because "how this arrives" is a fact about the supplier at least as often as about
     * the product: the same rice comes from one mill in 50 kg bags and from another in 25 kg bags.
     *
     * <p>The supplier's pack is added only when it genuinely differs from the product's own
     * (same container AND same size means one option, not two identical ones), and it never
     * becomes the default - section 2.1 pins the default to the product's own pack. Changing
     * which pack is preselected because of who is delivering would silently rewrite what the
     * number in an already-typed quantity box means.
     */
    public static List<UnitOption> forProductAndSupplier(
            String stockUnitCode,
            String packagingUnit,
            BigDecimal packagingSize,
            String supplierPackagingUnit,
            BigDecimal supplierPackagingSize) {
        return build(stockUnitCode, packagingUnit, packagingSize, supplierPackagingUnit, supplierPackagingSize);
    }

    /**
     * Section 3.1's per-request pack override: adds ONE option to an existing set for the
     * duration of one request. <b>It extends the set; it does not bypass matching.</b>
     *
     * <p>That distinction is the entire fix for P1-1, the reported complaint. The old code let a
     * request carry {@code unit} plus a packaging pair and hand-rolled its own two-branch
     * comparison, so the answer to "which units does this product accept" depended on which code
     * path you asked. Now there is one list; a per-delivery pack that is not on it becomes a
     * member of it, and resolution happens exactly once, against the list, for every request
     * alike.
     *
     * <p>An override naming a container the set already has (the ordinary "this delivery came in
     * a 25 kg bag rather than the usual 50" case) <b>replaces</b> that entry in place, keeping
     * its position and its {@code isDefault} - it is more specific than the standing default and
     * a set with two different "Bag of N" entries would make the picker ask an impossible
     * question. Anything else is appended, never default.
     *
     * @param options the base set, unmodified - a new list is returned.
     * @param packagingUnit the container this one delivery arrived in; null or unrecognised
     *     leaves the set untouched.
     * @param packagingSize how many stock units it held; null or non-positive leaves the set
     *     untouched, because a pack with no size is not a conversion.
     * @param stockUnitCode the product's stock unit, needed only to spell the label's tail
     *     ("Bag of 25 <b>kg</b>").
     */
    public static List<UnitOption> extendedWith(
            List<UnitOption> options, String packagingUnit, BigDecimal packagingSize, String stockUnitCode) {
        if (packagingUnit == null || packagingUnit.isBlank() || packagingSize == null || packagingSize.signum() <= 0) {
            return options;
        }
        Optional<UnitOfMeasure> container = UnitOfMeasure.fromCode(packagingUnit);
        String code = container.map(UnitOfMeasure::code).orElse(packagingUnit.trim().toUpperCase(Locale.ROOT));
        String label = packLabel(container.map(UnitOfMeasure::label).orElse(code), packagingSize, stockUnitCode);

        List<UnitOption> extended = new ArrayList<>(options.size() + 1);
        boolean replaced = false;
        for (UnitOption existing : options) {
            if (existing.code().equalsIgnoreCase(code) && !existing.isStockUnit()) {
                extended.add(new UnitOption(code, label, normalise(packagingSize), false, existing.isDefault(), true));
                replaced = true;
            } else {
                extended.add(existing);
            }
        }
        if (!replaced) {
            extended.add(new UnitOption(code, label, normalise(packagingSize), false, false, true));
        }
        return List.copyOf(extended);
    }

    /**
     * Section 3.1's resolution step: which member of the set a submitted {@code unit} names.
     * Case-insensitive on the code, because {@code "kg"} and {@code "KG"} are the same answer
     * and refusing one of them would be a riddle rather than a validation.
     *
     * <p>Null or blank resolves to the <b>stock unit</b> option, not to empty - contract
     * non-negotiable 8. Every caller that predates this remediation omits {@code unit}, means
     * "the unit everything is already stored in", and must keep behaving byte-for-byte as it
     * does today; making the absent case walk the same code path as the present one is how that
     * is guaranteed rather than hoped for.
     *
     * <p>Empty means "not one of this product's units" - the 400 of section 3.1, whose message
     * has to name every valid option. See {@link #countedInPhrase}.
     */
    public static Optional<UnitOption> resolve(List<UnitOption> options, String unit) {
        if (unit == null || unit.isBlank()) {
            return stockUnitOption(options);
        }
        String wanted = unit.trim();
        return options.stream().filter(option -> option.code().equalsIgnoreCase(wanted)).findFirst();
    }

    /** The set's stock-unit member - factor 1, what every stored quantity is counted in. */
    public static Optional<UnitOption> stockUnitOption(List<UnitOption> options) {
        return options.stream().filter(UnitOption::isStockUnit).findFirst();
    }

    /** The member a form preselects. Exactly one per set, by construction. */
    public static Optional<UnitOption> defaultOption(List<UnitOption> options) {
        return options.stream().filter(UnitOption::isDefault).findFirst();
    }

    /**
     * Section 1's Pack phrase - {@code "Bag of 50 kg"} - the ONLY rendering of a
     * {@code packagingUnit}/{@code packagingSize} pair allowed anywhere in the product, in a
     * spreadsheet cell, or in a message. The two fields are one idea and were being shown as two
     * ("Delivered as" plus a unitless "Pack size"), which is why the remediation plan's P2 lists
     * a pack size field that does not say what it is a size OF.
     *
     * <p>The size is rendered the way a person writes it - {@code 50}, not {@code 50.00} - via
     * {@code ImportCopy.count}, the same formatter every other user-facing number in this
     * codebase goes through.
     */
    public static String packLabel(String containerLabel, BigDecimal packagingSize, String stockUnitCode) {
        String tail = symbolOf(stockUnitCode);
        return containerLabel + " of " + ImportCopy.count(packagingSize) + (tail.isEmpty() ? "" : " " + tail);
    }

    /**
     * "kg or bags of 50 kg" - every valid answer, joined the way a person lists them, for
     * section 3.1's unknown-unit message. Contract non-negotiable 4's principle applied to an
     * error rather than a cell: never ask a question without showing what its answers are.
     *
     * <p>Each option is put through {@link #spokenPhrase}, which is what turns the picker label
     * "Bag of 50 kg" into the mid-sentence "bags of 50 kg". A message that spliced labels in raw
     * would read "counted in kg or Bag of 50 kg", and the capital B mid-sentence is exactly the
     * tell that a machine assembled the sentence.
     */
    public static String countedInPhrase(List<UnitOption> options) {
        return ImportCopy.orList(options.stream().map(UnitOptions::spokenPhrase).toList());
    }

    /**
     * One option as it reads inside a sentence: {@code "kg"}, {@code "bags of 50 kg"},
     * {@code "pieces"}.
     *
     * <h2>The two grammar rules, and why they are not one</h2>
     * A symbol abbreviated in parentheses is never pluralised and never lower-cased - you write
     * "3 kg" and "3 L", not "3 kgs" or "3 l". A word is both - "3 pieces", "3 bags of 50 kg". So
     * the split is on whether {@link UnitOfMeasure#hasSymbolAbbreviation()}, not on role or
     * category. For a pack phrase only the container pluralises: "bags of 50 kg", never "bags of
     * 50 kgs", because the 50 kg is one measurement, not a countable thing.
     */
    public static String spokenPhrase(UnitOption option) {
        String label = option.label();
        int of = label.indexOf(" of ");
        if (of > 0) {
            return pluralise(label.substring(0, of).toLowerCase(Locale.ROOT)) + label.substring(of);
        }
        return UnitOfMeasure.fromCode(option.code())
                .map(UnitOptions::spokenPhrase)
                .orElseGet(() -> pluralise(label.toLowerCase(Locale.ROOT)));
    }

    /**
     * {@link #spokenPhrase(UnitOption)} for a bare unit - the unit a user asked for that the
     * product does not have ("we don't know how to count it in <b>cartons</b>"). Same two
     * grammar rules.
     */
    public static String spokenPhrase(UnitOfMeasure unit) {
        return unit.hasSymbolAbbreviation() ? unit.symbol() : pluralise(unit.label().toLowerCase(Locale.ROOT));
    }

    /**
     * How a unit a user typed should be echoed back at them. A recognised code becomes its
     * spoken phrase ("CARTON" &rarr; "cartons"); anything else is quoted verbatim, because
     * repeating a typo back unquoted reads as if we understood it.
     */
    public static String spokenPhraseOfSubmitted(String unit) {
        return UnitOfMeasure.fromCodeOrLabel(unit)
                .map(UnitOptions::spokenPhrase)
                .orElseGet(() -> ImportCopy.quote(unit == null ? "" : unit.trim()));
    }

    /**
     * The short symbol for a stored stock-unit code - {@code "KG"} &rarr; {@code "kg"} - falling
     * back to the code itself for a value this catalog does not know (nothing writes one today;
     * a pre-V17 row might). Empty string for no unit at all, so a caller composing a label can
     * append it unconditionally.
     */
    public static String symbolOf(String stockUnitCode) {
        if (stockUnitCode == null || stockUnitCode.isBlank()) {
            return "";
        }
        return UnitOfMeasure.fromCode(stockUnitCode).map(UnitOfMeasure::symbol).orElse(stockUnitCode);
    }

    // ---------------------------------------------------------------------------------------
    // Section 2.1's algorithm, once.
    // ---------------------------------------------------------------------------------------

    private static List<UnitOption> build(
            String stockUnitCode,
            String packagingUnit,
            BigDecimal packagingSize,
            String supplierPackagingUnit,
            BigDecimal supplierPackagingSize) {

        Optional<UnitOfMeasure> stockUnit = UnitOfMeasure.fromCode(stockUnitCode);
        boolean hasOwnPack = isRealPack(packagingUnit, packagingSize);
        // Section 2.1: the product's own pack if it has one, else the stock unit. Never a
        // supplier's pack and never a base unit - see forProductAndSupplier and step 4.
        boolean stockUnitIsDefault = !hasOwnPack;

        Map<String, UnitOption> byCode = new LinkedHashMap<>();

        // ---- 1. the stock unit itself.
        if (stockUnitCode == null || stockUnitCode.isBlank()) {
            // The pre-V17 product that never got a unit. It still has to be countable, so it
            // gets one nameless option rather than an empty set a form could not render.
            byCode.put(
                    NO_STOCK_UNIT_CODE,
                    new UnitOption(NO_STOCK_UNIT_CODE, NO_STOCK_UNIT_LABEL, BigDecimal.ONE, true, stockUnitIsDefault, false));
        } else {
            String code = stockUnit.map(UnitOfMeasure::code).orElse(stockUnitCode);
            byCode.put(code, new UnitOption(code, symbolOf(stockUnitCode), BigDecimal.ONE, true, stockUnitIsDefault, false));
        }

        // ---- 2. the product's own pack.
        if (hasOwnPack) {
            putPack(byCode, packagingUnit, packagingSize, stockUnitCode, true);
        }

        // ---- 3. this supplier's pack, when it differs from step 2's.
        if (isRealPack(supplierPackagingUnit, supplierPackagingSize)) {
            putPack(byCode, supplierPackagingUnit, supplierPackagingSize, stockUnitCode, false);
        }

        // ---- 4. same-category base units with a static factor (section 2.2).
        stockUnit.ifPresent(base -> {
            for (UnitOfMeasure candidate : UnitOfMeasure.baseUnits()) {
                if (candidate == base) {
                    continue;
                }
                candidate.factorTo(base)
                        .ifPresent(factor -> byCode.putIfAbsent(
                                candidate.code(),
                                new UnitOption(candidate.code(), candidate.symbol(), factor, false, false, false)));
            }
        });

        return List.copyOf(new ArrayList<>(byCode.values()));
    }

    /**
     * Adds a pack option under the container's canonical code. {@code putIfAbsent}, so section
     * 2.1's "deduplicated by code, first occurrence wins" holds without the caller checking:
     * a supplier whose default pack matches the product's contributes nothing, and one whose
     * pack uses the same container in a different size is deliberately NOT a second entry -
     * two options both labelled "Bag of ..." in one picker is a question with no readable answer.
     */
    private static void putPack(
            Map<String, UnitOption> byCode,
            String packagingUnit,
            BigDecimal packagingSize,
            String stockUnitCode,
            boolean isDefault) {
        Optional<UnitOfMeasure> container = UnitOfMeasure.fromCode(packagingUnit);
        String code = container.map(UnitOfMeasure::code).orElse(packagingUnit.trim().toUpperCase(Locale.ROOT));
        String label = packLabel(container.map(UnitOfMeasure::label).orElse(code), packagingSize, stockUnitCode);
        byCode.putIfAbsent(code, new UnitOption(code, label, normalise(packagingSize), false, isDefault, true));
    }

    /** A pack is only a conversion when it names a container AND says how much it holds. */
    private static boolean isRealPack(String packagingUnit, BigDecimal packagingSize) {
        return packagingUnit != null
                && !packagingUnit.isBlank()
                && packagingSize != null
                && packagingSize.signum() > 0;
    }

    /**
     * {@code 50.00} as stored in a {@code numeric(14,2)} column becomes {@code 50} on the wire.
     * Cosmetic for the arithmetic - the two compare equal - and not cosmetic at all for the
     * reader: {@code factorToStockUnit} is echoed into a spreadsheet's reference column and a
     * conversion preview, and "Bag of 50.00 kg" is the kind of detail that makes a user distrust
     * every other number on the screen.
     */
    private static BigDecimal normalise(BigDecimal factor) {
        BigDecimal stripped = factor.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String pluralise(String word) {
        return word.endsWith("s") ? word : word + "s";
    }
}
