package com.procurepal_services.stock_bridge_api.product.unit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One unit a quantity for a particular product may be entered in, together with everything
 * needed to turn a number typed in it into the two numbers the ledger stores: a quantity in the
 * product's <b>stock unit</b> and a price <b>per stock unit</b>. The one new abstraction of
 * UNIT_UX_CONTRACT.md section 2, and the reason it exists is UNIT_UX_REMEDIATION_PLAN.md
 * section 1: the domain has three concepts (stock unit, pack, entry unit) and the system had a
 * conversion for the quantity half of an entry and none for the price half, so "20 bags at
 * &#8358;45,000/bag" was recorded as 1,000 kg at &#8358;45,000 <em>per kg</em>. A UnitOption
 * carries the single factor that both halves are derived from, so the two can no longer drift.
 *
 * <h2>Derived, never stored</h2>
 * There is no unit_options table and there is not going to be one. A product's set is computed
 * from its own {@code unitOfMeasure}/{@code packagingUnit}/{@code packagingSize} (and, for a
 * supplier-scoped set, that supplier's default pack) by {@link UnitOptions}, which is the ONE
 * implementation of section 2.1's algorithm - every caller, on both sides of the wire and in
 * both spreadsheets, reads from that. A second copy would be the one that drifts, which is
 * precisely the failure this whole remediation is undoing.
 *
 * <h2>Units, spelled out</h2>
 * <ul>
 *   <li>{@code factorToStockUnit} - multiply a quantity entered in this unit by this to reach
 *       stock units; DIVIDE a price entered per this unit by it to reach a price per stock unit.
 *       Always strictly positive. {@code 1} for the stock unit itself.</li>
 *   <li>{@code label} - user-facing, and the only string a UI or a spreadsheet cell may show for
 *       this option. Never the {@code code}: contract non-negotiable 4 forbids an internal code
 *       in a cell, and section 1 locks a pack's rendering to the single phrase "Bag of 50 kg".</li>
 *   <li>{@code code} - the value that travels back on the wire as a request's {@code unit}, and
 *       the key {@link UnitOptions#resolve} matches against.</li>
 *   <li>{@code isDefault} - exactly one true per set; what a form preselects.</li>
 * </ul>
 *
 * @param code the {@link UnitOfMeasure} code, or the product's stock unit code; empty string for
 *     the "units" placeholder set of a product that has no stock unit configured at all.
 * @param label the user-facing phrase - a short symbol ("kg") for a base unit, section 1's Pack
 *     phrase ("Bag of 50 kg") for a pack.
 * @param factorToStockUnit how many stock units one of this unit is. Strictly positive.
 * @param isStockUnit whether this option IS the product's stock unit (factor 1, the unit every
 *     stored quantity is counted in).
 * @param isDefault whether a form should preselect this option. Exactly one per set.
 */
public record UnitOption(
        String code,
        String label,
        BigDecimal factorToStockUnit,
        boolean isStockUnit,
        boolean isDefault,
        boolean isPack) {

    /**
     * True when this option came from a product's or supplier's PACK (section 2.1 steps 2 and 3),
     * false for the stock unit itself and for the same-category base units of step 4.
     *
     * <h2>Why the set has to say so rather than callers working it out</h2>
     * Two callers used to infer it from {@link UnitOfMeasure#role()} - "not a BASE-role code, so
     * it must be a pack". That held only while the role split was a hard gate. Now that COUNT
     * units serve either role, a turmeric sold in 34 g pieces has a PACK whose code
     * ({@code PIECE}) is declared BASE, and every such inference silently returns the wrong
     * answer - reporting no pack override on a delivery that has one.
     *
     * <p>The builder knows which step produced each option, so it records it. An inference that
     * only worked because of a constraint we have just relaxed is not worth preserving.
     */
    public boolean isPack() {
        return isPack;
    }

    /**
     * Scale the price arithmetic is done at before anything is persisted - UNIT_UX_CONTRACT.md
     * section 3.2, "persist at each column's own scale; do the arithmetic at scale 6 first".
     * Six rather than the two the money columns store because a per-stock-unit price is a
     * QUOTIENT: &#8358;45,000 per bag of 33 is &#8358;1,363.636363... per kg, and rounding that
     * to two decimals before it is multiplied back out by a four-digit quantity moves the total
     * by naira. Rounding once, at the column, is the only place the loss is bounded.
     */
    private static final int PRICE_SCALE = 6;

    /**
     * The number of stock units {@code enteredQuantity} of this unit is - {@code round(quantity
     * &times; factorToStockUnit)}, HALF_UP, scale 0 (UNIT_UX_CONTRACT.md section 3.1). Stock
     * quantities are integers everywhere in this schema ({@code stock_movements.quantity},
     * {@code products.quantity_on_hand}), so the rounding is not a choice this method makes, it
     * is the shape of the column it feeds.
     *
     * <p><b>Can legitimately return 0</b>, and the caller must treat that as an error rather
     * than a quantity - 1 g on a KG product is 0.001 kg, which rounds to nothing at all.
     * Section 3.1 requires a 400 there, never a silent zero, because silently recording "we
     * received nothing" for a delivery somebody typed is the same class of defect as recording
     * the wrong number. The check is not made here because the message needs the product's name
     * and its stock unit, which this record does not carry; see
     * {@code StockManagementService.resolveEntry}.
     *
     * @param enteredQuantity a quantity counted in THIS unit (bags, tonnes, kg).
     * @return the same amount counted in the product's stock unit.
     */
    public int toStockUnitQuantity(int enteredQuantity) {
        if (isUnitFactor()) {
            return enteredQuantity;
        }
        return factorToStockUnit
                .multiply(BigDecimal.valueOf(enteredQuantity))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /**
     * {@code resolveBasePrice} of UNIT_UX_CONTRACT.md section 3.2: {@code enteredPrice /
     * factorToStockUnit}, scale 6, HALF_UP. This is the half of the conversion that did not
     * exist before this remediation, and its absence is P0-1 - a price entered per bag was
     * blended into a weighted average against a quantity in kg, producing a catalog cost price
     * 50&times; too high, silently, and then compounded into every later average.
     *
     * <p>Returns the input <b>unchanged</b>, not a rescaled copy, when the factor is 1. That is
     * contract non-negotiable 8 made mechanical: a request that omits {@code unit} resolves to
     * the stock unit, whose factor is 1, and must therefore write byte-for-byte what today's
     * code writes. A {@code divide(ONE, 6, HALF_UP)} would return a numerically equal but
     * differently-scaled BigDecimal, which is a difference an equals-based test can see.
     *
     * @param enteredPrice a price per ONE of this unit (per bag, per tonne), or null - a
     *     stock-in with no price (a free sample, a correction) is ordinary and stays null all
     *     the way down rather than becoming a zero that would drag a weighted average.
     * @return the same money expressed per ONE stock unit, or null if {@code enteredPrice} was.
     */
    public BigDecimal toStockUnitPrice(BigDecimal enteredPrice) {
        if (enteredPrice == null || isUnitFactor()) {
            return enteredPrice;
        }
        return enteredPrice.divide(factorToStockUnit, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** True when this option needs no arithmetic at all - the stock unit, or any factor of 1. */
    private boolean isUnitFactor() {
        return factorToStockUnit.compareTo(BigDecimal.ONE) == 0;
    }
}
