package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.product.unit.Decimals;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureCategory;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 *   <li>{@link #comesInLabel} and {@link #canonical} - the stock sheet's "Comes in" wording
 *       ("Bag · 50 kg", "Loose · kg"), written and read back.</li>
 *   <li>{@link #rowOptions} - which ways of buying a product get a row of their own.</li>
 *   <li>{@link #lastPricePerStockUnit} - the "Last price paid" figure, shared by the template and
 *       the import so a blank price means the same number in both.</li>
 *   <li>{@link #resolve} - reading a "Comes in" cell back when there is no product in hand yet,
 *       which is a question only a file parser asks.</li>
 * </ol>
 */
public final class SheetUnitOptions {

    /**
     * Splits a pack label back into the container that starts it and the size that names it:
     * {@code "Bag of 50 kg"} to {@code "Bag"} and {@code 50}. See {@link #resolve} for why the
     * reader needs the container and why {@code UnitOfMeasure.fromCodeOrLabel} cannot supply it;
     * see {@link #resolvePack} for why {@link #resolve} alone is not enough to accept a cell
     * safely. The number is required, not optional: every label this pattern is meant to read
     * back is one {@code UnitOptions.packLabel} itself composed, and that method never omits it.
     */
    private static final Pattern PACK_LABEL =
            Pattern.compile("^(.*?)\\s+of\\s+([\\d,]+(?:\\.\\d+)?)\\s*\\S+$", Pattern.CASE_INSENSITIVE);

    private SheetUnitOptions() {
    }

    /**
     * A product's unit set for the stock-in sheet - section 2.1 steps 1 to 4, including EVERY ONE
     * of the preferred supplier's packs (MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-6), because
     * "how this arrives" is a fact about the supplier at least as often as about the product, and
     * a supplier is no longer limited to one pack. This is the concrete payoff of that design's
     * finding that the sheet's column "is built to accept it later without another rewrite" - it
     * was telling the truth, and this list is the only change that was needed to cash it in.
     *
     * <p>A one-line delegation kept as a named method rather than inlined at the call site: it is
     * the single place the sheets decide <em>which</em> of {@link UnitOptions}' entry points is
     * theirs, and having it named is what makes that choice reviewable.
     */
    public static List<UnitOption> forRow(
            String stockUnitCode,
            String packagingUnitCode,
            java.math.BigDecimal packagingSize,
            List<UnitOptions.PackSpec> supplierPacks) {
        return UnitOptions.forProductAndSupplier(stockUnitCode, packagingUnitCode, packagingSize, supplierPacks);
    }

    /** Between a container and its size on the sheet: "Bag · 50 kg". */
    public static final String COMES_IN_SEPARATOR = " · ";

    private static final Pattern LOOSE_PREFIX =
            Pattern.compile("^loose\\b\\s*[·:\\-]?\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * The "Comes in" cell for one way of buying a product (BULK_IMPORT_CX_PLAN.md task 1.4) -
     * written by the template and offered by the review grid's picker, so both say the same thing:
     * <ul>
     *   <li>a pack: {@code "Bag · 50 kg"}, {@code "Pack · 10 pieces"};</li>
     *   <li>a measured unit bought loose: {@code "Loose · kg"};</li>
     *   <li>a counted stock unit: its own word, {@code "Piece"} - "loose pieces" says nothing more;</li>
     *   <li>no stock unit at all: {@code "Units"}.</li>
     * </ul>
     */
    public static String comesInLabel(UnitOption option) {
        if (option.isPack()) {
            String label = option.label();
            int of = label.indexOf(" of ");
            return of < 0 ? label : label.substring(0, of) + COMES_IN_SEPARATOR + label.substring(of + 4);
        }
        if (option.code() == null || option.code().isBlank()) {
            return "Units";
        }
        Optional<UnitOfMeasure> unit = UnitOfMeasure.fromCode(option.code());
        if (unit.isPresent() && unit.get().category() == UnitOfMeasureCategory.COUNT) {
            return unit.get().label();
        }
        return "Loose" + COMES_IN_SEPARATOR + option.label();
    }

    /**
     * A "Comes in" cell rewritten into the grammar the older readers already understand -
     * {@code "Bag · 50 kg"} to {@code "Bag of 50 kg"}, {@code "Loose · kg"} to {@code "kg"} - so
     * {@link #resolvePack} and the new-pack parser stay the only readers of a size. Anything else
     * comes back trimmed and otherwise unchanged. A non-breaking space, which a paste out of a
     * browser or a Numbers export leaves behind, is read as a space.
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace(' ', ' ').trim();
        Matcher loose = LOOSE_PREFIX.matcher(text);
        if (loose.find() && loose.end() < text.length()) {
            return text.substring(loose.end()).trim();
        }
        int dot = text.indexOf('·');
        if (dot > 0 && dot < text.length() - 1) {
            return text.substring(0, dot).trim() + " of " + text.substring(dot + 1).trim();
        }
        return text;
    }

    /**
     * The ways of buying a product that each get their own row on the stock sheet: every pack
     * (the product's own first, as the set orders them), then the stock unit. Never the
     * same-category base units - a row for "Loose · mg" of rice would be noise.
     */
    public static List<UnitOption> rowOptions(List<UnitOption> options) {
        List<UnitOption> rows = new ArrayList<>();
        options.stream().filter(UnitOption::isPack).forEach(rows::add);
        options.stream().filter(UnitOption::isStockUnit).findFirst().ifPresent(rows::add);
        return rows;
    }

    /**
     * What was last paid for ONE stock unit bought this way - the figure behind the sheet's
     * "Last price paid", and behind a blank "Price paid for one" at import time, so the two agree.
     *
     * <p>Looked for in order: the pack that IS this way of buying (same container and size, or a
     * container-less price for a loose row); then the default pack; then the product's own cost
     * price. Every one of those is stored per stock unit (contract section 3.2).
     *
     * @param packs supplier packs to look in, the most specific supplier's first.
     */
    public static BigDecimal lastPricePerStockUnit(
            List<ProductVendorPack> packs, UnitOption option, BigDecimal productCostPrice) {
        for (ProductVendorPack pack : packs) {
            if (pack.getLastCostPrice() != null && isThisWayOfBuying(pack, option)) {
                return pack.getLastCostPrice();
            }
        }
        for (ProductVendorPack pack : packs) {
            if (pack.getLastCostPrice() != null && pack.isDefault()) {
                return pack.getLastCostPrice();
            }
        }
        return productCostPrice;
    }

    /** {@link #lastPricePerStockUnit} for ONE of {@code option} - per bag for a bag row. Null when unknown. */
    public static BigDecimal lastPricePerOption(
            List<ProductVendorPack> packs, UnitOption option, BigDecimal productCostPrice) {
        BigDecimal perStockUnit = lastPricePerStockUnit(packs, option, productCostPrice);
        if (perStockUnit == null) {
            return null;
        }
        // Not rounded: a tiny per-gram price rounded to two places would record as zero. Plain
        // rather than stripped, because N42,000 reaching the delivery screen as "4.2E+4" is not a
        // price anybody recognises - see Decimals.
        return Decimals.plain(perStockUnit.multiply(option.factorToStockUnit()));
    }

    private static boolean isThisWayOfBuying(ProductVendorPack pack, UnitOption option) {
        if (!option.isPack()) {
            return pack.getPackagingUnit() == null;
        }
        if (pack.getPackagingUnit() == null || pack.getPackagingSize() == null) {
            return false;
        }
        String code = UnitOfMeasure.fromCode(pack.getPackagingUnit())
                .map(UnitOfMeasure::code)
                .orElse(pack.getPackagingUnit());
        return option.code().equalsIgnoreCase(code)
                && pack.getPackagingSize().compareTo(option.factorToStockUnit()) == 0;
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
     * the parse of a file stay independent of any product.
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
     * <p>The size in the label is not checked <em>here</em> - it cannot be, because this
     * method has no product's set to check it against (see the note above on why this is not
     * {@code UnitOptions.resolve}). A caller that DOES have that set in hand - {@code
     * StockInRowHandler.validateCountedIn} - must use {@link #resolvePack} instead and compare
     * the size itself: a cell reading "Bag of 50 g" whose product's BAG pack has since become
     * 1,000 g, at a different vendor or on the product's own configuration, would otherwise
     * resolve on the container word alone and silently record 1,000 g for a delivery that said
     * 50 - the exact silent-wrong-number failure mode this whole remediation exists to prevent,
     * reached here from a stale or hand-typed cell instead of a first-time guess.
     */
    public static Optional<UnitOfMeasure> resolve(String rawCellValue) {
        rawCellValue = canonical(rawCellValue);
        Optional<UnitOfMeasure> direct = UnitOfMeasure.fromCodeOrLabel(rawCellValue);
        if (direct.isPresent() || rawCellValue == null) {
            return direct;
        }
        return resolvePack(rawCellValue).flatMap(pack -> UnitOfMeasure.fromCode(pack.code()));
    }

    /**
     * {@link #resolve}'s size-aware sibling: a composed pack label read back with the number
     * still attached, so {@code StockInRowHandler.validateCountedIn} can require it to match
     * the option it resolves to rather than accepting any pack that merely shares its
     * container word.
     *
     * @return empty for a bare unit cell ({@code "kg"} - use {@link #resolve} instead, there
     *     is no size to compare) or for text that does not parse as {@code
     *     UnitOptions.packLabel}'s own "container of size unit" shape.
     */
    public static Optional<ParsedPackLabel> resolvePack(String rawCellValue) {
        if (rawCellValue == null) {
            return Optional.empty();
        }
        // A non-breaking space is what a paste out of a browser or a Numbers export leaves
        // behind, and it would stop " of " matching for a reason nobody could see.
        Matcher matcher = PACK_LABEL.matcher(canonical(rawCellValue));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<UnitOfMeasure> container = UnitOfMeasure.fromCodeOrLabel(matcher.group(1));
        if (container.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParsedPackLabel(
                    container.get().code(), new java.math.BigDecimal(matcher.group(2).replace(",", ""))));
        } catch (NumberFormatException notActuallyNumeric) {
            return Optional.empty();
        }
    }

    /** {@link #resolvePack}'s result: which container, and which size the cell named it with. */
    public record ParsedPackLabel(String code, java.math.BigDecimal size) {
    }


}
