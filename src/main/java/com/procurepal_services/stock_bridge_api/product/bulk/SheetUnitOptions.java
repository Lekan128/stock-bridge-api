package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three things the two spreadsheets need from a product's <b>unit set</b> that no other
 * surface needs - and nothing else.
 *
 * <h2>What is NOT here, and why that matters</h2>
 * UNIT_UX_CONTRACT.md section 2.1's algorithm, section 2.2's factor table, the Pack phrase
 * ("Bag of 50 kg"), the short symbol, and "which option does a form preselect" all live in
 * {@link UnitOptions}, which is the ONE implementation of them. This class holds no copy of any
 * of it and computes no factor of its own - it calls
 * {@link UnitOptions#forProductAndSupplier} and formats the answer.
 *
 * <p>That is not tidiness, it is the whole point of the remediation. UNIT_UX_REMEDIATION_PLAN.md
 * section 1 traces every defect back to the same shape: three concepts, six surfaces, four
 * vocabularies, and two surfaces quietly interpreting the same number differently. A second
 * implementation of "which units may this product be counted in" - even a correct one - is the
 * seed of the next such divergence, and the spreadsheets are the surface where it would be
 * hardest to notice, because nobody reviews a generated .xlsx.
 *
 * <h2>What IS here</h2>
 * <ol>
 *   <li>{@link #howYouCountIt} - the reference column's text, joined the way section 5.2 words it.
 *       A cell, not a sentence, so it cannot reuse {@code UnitOptions.countedInPhrase}, which
 *       composes the middle of an error message ("kg or bags of 50 kg") in a different grammar.</li>
 *   <li>{@link #resolve} - reading a {@code counted_in} cell back when there is no product in hand
 *       yet, which is a question only a file parser asks.</li>
 *   <li>{@link #dropdownLabels} - the flat validation list, which is a spreadsheet mechanic.</li>
 * </ol>
 */
public final class SheetUnitOptions {

    /**
     * How the stock-in sheet joins a product's ways of counting into one readable cell -
     * UNIT_UX_CONTRACT.md section 5.2. Reads as something a person scans rather than parses:
     * "kg · or Bag of 50 kg".
     */
    private static final String OPTION_SEPARATOR = " · or ";

    /**
     * Splits a pack label back into the container that starts it: {@code "Bag of 50 kg"} to
     * {@code "Bag"}. See {@link #resolve} for why the reader needs this and why
     * {@code UnitOfMeasure.fromCodeOrLabel} cannot supply it.
     */
    private static final Pattern PACK_LABEL = Pattern.compile("^(.*?)\\s+of\\s+.+$", Pattern.CASE_INSENSITIVE);

    private SheetUnitOptions() {
    }

    /**
     * A product's unit set for the stock-in sheet - section 2.1 steps 1 to 4, including the
     * preferred supplier's own pack, because "how this arrives" is a fact about the supplier at
     * least as often as about the product and the sheet already knows which supplier the row is
     * for.
     *
     * <p>A one-line delegation kept as a named method rather than inlined at the call site: it is
     * the single place the sheets decide <em>which</em> of {@link UnitOptions}' entry points is
     * theirs, and having it named is what makes that choice reviewable.
     */
    public static List<UnitOption> forRow(
            String stockUnitCode,
            String packagingUnitCode,
            java.math.BigDecimal packagingSize,
            String supplierPackagingUnitCode,
            java.math.BigDecimal supplierPackagingSize) {
        return UnitOptions.forProductAndSupplier(
                stockUnitCode, packagingUnitCode, packagingSize, supplierPackagingUnitCode, supplierPackagingSize);
    }

    /**
     * The {@code how_you_count_it} cell - UNIT_UX_CONTRACT.md section 5.2, the column that fixes
     * the reported complaint by putting the valid answers on the row, beside the cell that asks
     * the question.
     *
     * <h2>Deviation from the contract, stated plainly</h2>
     * The contract says "built from section 2.1's set" and gives the worked example
     * {@code "kg · or Bag of 50 kg"} for a KG/BAG/50 product. Those two cannot both be satisfied:
     * section 2.1 step 4 also puts mg, g and t in that product's set, so the literal reading would
     * print {@code "kg · or Bag of 50 kg · or mg · or g · or t"}. This method follows the worked
     * example - steps 1 to 3, the ways this particular product is actually bought and sold - and
     * the column's header comment carries the rest ("other sizes of the same measure work too").
     *
     * <p>The reason is the whole purpose of the column: it exists because the sheet was confusing,
     * and a cell listing milligrams of rice would be confusing in a new way. Nothing is lost -
     * the SET is untouched, the {@code counted_in} dropdown still offers every base unit, and the
     * server still accepts them. Only what is printed on the row is narrowed.
     *
     * <p>The steps are told apart by role rather than by position, so this stays correct if
     * {@link UnitOptions} ever reorders: an option is one of the product's OWN ways of counting
     * when it is the stock unit, or when its code is not a BASE-role unit (i.e. it is a pack).
     */
    public static String howYouCountIt(List<UnitOption> options) {
        return String.join(
                OPTION_SEPARATOR,
                options.stream().filter(SheetUnitOptions::isProductsOwnWayOfCounting).map(UnitOption::label).toList());
    }

    /**
     * Reads a {@code counted_in} cell back, accepting everything either sheet ever wrote plus
     * everything {@code UnitOfMeasure.fromCodeOrLabel} already accepted.
     *
     * <h2>Why this is not {@code UnitOptions.resolve}</h2>
     * That one answers "which member of THIS product's set does this code name", and it is the
     * right call once a product is in hand - it is what the row handler and
     * {@code StockManagementService} use. A file parser is upstream of that: it has a SKU it has
     * not looked up yet, so it has no set to match against, and its job is only to turn what the
     * cell says into a code the row handler can then check. Keeping the two apart is what lets
     * {@link StockInExcelService#parse} be a pure function of the file.
     *
     * <h2>Why the pack label needs its own step</h2>
     * {@code fromCodeOrLabel} resolves codes, display labels and trade aliases - {@code "BAG"},
     * {@code "Bag"}, {@code "bags"}, {@code "BG"}. It cannot resolve {@code "Bag of 50 kg"},
     * because that string is not a unit: it is a unit <em>and a product's pack size</em>, composed
     * by {@code UnitOptions.packLabel} for display. Teaching {@code UnitOfMeasureAliases} about it
     * would be wrong twice over - the size varies per product, so the table could never be closed,
     * and that file belongs to M1. So the composition is undone here and the remainder goes to the
     * existing resolver unchanged.
     *
     * <p>The size in the label is ignored, not verified. It describes the product's configured
     * pack, not a per-delivery override: section 5.2 removed {@code packaging_size} from the sheet
     * precisely so a delivery row can no longer redefine a stored product attribute.
     */
    public static Optional<UnitOfMeasure> resolve(String rawCellValue) {
        Optional<UnitOfMeasure> direct = UnitOfMeasure.fromCodeOrLabel(rawCellValue);
        if (direct.isPresent() || rawCellValue == null) {
            return direct;
        }
        // A non-breaking space is what a paste out of a browser or a Numbers export leaves
        // behind, and it would stop " of " matching for a reason nobody could see.
        Matcher matcher = PACK_LABEL.matcher(rawCellValue.replace(' ', ' ').trim());
        if (matcher.matches()) {
            return UnitOfMeasure.fromCodeOrLabel(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Every label the {@code counted_in} dropdown offers - UNIT_UX_CONTRACT.md section 5.2: the
     * packs actually in use across the sheet's products, then every base unit's symbol.
     *
     * <p>Flat, not per-row, and BULK_IMPORT_DESIGN.md section 8.3 has not changed its mind about
     * why: per-row {@code INDIRECT} dropdowns break in Google Sheets, Numbers and LibreOffice.
     * What HAS changed is that the flat list is no longer the only guidance on the row -
     * {@link #howYouCountIt} now carries the per-row answer, so the dropdown can afford to be the
     * generous fallback it always was rather than the only thing standing between the user and a
     * wrong cell.
     */
    public static List<String> dropdownLabels(List<UnitOption> optionsInUse) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        optionsInUse.stream()
                .filter(UnitOption::isPack)
                .map(UnitOption::label)
                .forEach(labels::add);
        UnitOfMeasure.baseUnits().stream().map(UnitOfMeasure::symbol).forEach(labels::add);
        return List.copyOf(labels);
    }

    /** Steps 1 to 3 of section 2.1 - this product's stock unit and its packs, never step 4's base units. */
    private static boolean isProductsOwnWayOfCounting(UnitOption option) {
        return option.isStockUnit() || option.isPack();
    }

}
