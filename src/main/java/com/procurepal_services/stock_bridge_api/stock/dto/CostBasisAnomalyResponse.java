package com.procurepal_services.stock_bridge_api.stock.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One product whose stored cost basis looks like it was written through the P0-1 path - a price
 * entered per PACK but recorded as if it were per stock unit. The output of the one-off audit
 * UNIT_UX_REMEDIATION_PLAN.md Phase 0 asks for, and nothing more than that.
 *
 * <h2>This reports. It never repairs.</h2>
 * Phase 0 is explicit, and the reasoning is worth restating where the code is: "existing
 * {@code lastCostPrice} and {@code costPrice} values written through the broken path cannot be
 * distinguished from correct ones after the fact - ship a one-off report [...] and let a human
 * resolve them. Do not attempt an automatic fix."
 *
 * <p>The indistinguishability is not a limitation of the detector, it is a property of the data.
 * A product whose cost really is &#8358;45,000 per kg (a precious metal, a reagent) and one whose
 * &#8358;900-per-kg rice was recorded per bag produce byte-identical rows. Nothing in the schema
 * separates them, because the fact that would have - which unit the human typed the price in -
 * was exactly the fact the old code failed to record. V21's {@code entered_unit} closes that hole
 * going forward and can do nothing for a row written before it existed. So this type carries the
 * EVIDENCE and leaves the verdict to somebody who knows what the product is.
 *
 * <h2>Every money figure here is per stock unit, as stored</h2>
 * Which is precisely the claim under suspicion: the audit exists because some of these values are
 * per PACK while the column says per stock unit. {@link #stockUnitSymbol} is published so a
 * caller can state the basis it is testing (non-negotiable 2) without re-deriving it.
 *
 * @param productId the product - an id as a FIELD, which is how every DTO here addresses a
 *     resource. {@link #summary} is the user-visible string and contains no id (non-negotiable 6).
 * @param productName the product's name, the subject of {@link #summary}.
 * @param sku its code, for a human reconciling against an invoice.
 * @param stockUnitSymbol what every price below is claimed to be per - "kg". Empty when the
 *     product never got a stock unit.
 * @param costPrice {@code Product.costPrice} as stored, claimed to be per one stock unit.
 * @param medianMovementPrice the median {@code unitPriceAtTime} across this product's priced
 *     {@code IN} movements, same claimed basis. Null when it has none - a cost seeded by a bulk
 *     import with no delivery behind it, which {@link #signals} then judges on selling price
 *     alone.
 * @param sellingPrice {@code Product.unitPrice}, per stock unit. Null for a buying company's own
 *     stock, which has no selling price at all.
 * @param packagingSize how many stock units one pack holds - the factor a per-pack price would
 *     have been inflated by, and the number {@link #largestRatio} is worth comparing against.
 *     The product's current pack, falling back to the one the suspect delivery was snapshotted
 *     with, since packaging can have been edited since that row was written.
 * @param largestRatio the biggest of the ratios the comparison signals tested, so a caller can
 *     sort worst-first without recomputing anything. <b>Null when only
 *     {@code ENTERED_IN_PACKS_BEFORE_FIX} fired</b>, because that signal measures nothing - it
 *     reports how a row was written - and publishing a 0 or a 1 would read as a measurement that
 *     was never taken. Absent on the wire when null (Jackson NON_NULL).
 * @param ratioMatchesPackSize whether {@link #largestRatio} lands within 10% of
 *     {@link #packagingSize}. <b>The strongest single piece of evidence in this record</b>: a
 *     cost inflated by almost exactly the number of kilograms in a bag is a per-bag price in a
 *     per-kg column, and is very unlikely to be a coincidence of pricing.
 * @param signals which detection rules fired, as stable machine-readable codes - see
 *     {@code CostBasisAuditService} for what each one means and what it can and cannot see.
 * @param summary the finding as a sentence, server-composed, no id and no column name.
 * @param suspectSupplierLines the same suspicion applied to this product's
 *     {@code ProductVendor.lastCostPrice} values, which were written through the very same path.
 */
public record CostBasisAnomalyResponse(
        UUID productId,
        String productName,
        String sku,
        String stockUnitSymbol,
        BigDecimal costPrice,
        BigDecimal medianMovementPrice,
        BigDecimal sellingPrice,
        BigDecimal packagingSize,
        BigDecimal largestRatio,
        boolean ratioMatchesPackSize,
        List<String> signals,
        String summary,
        List<SuspectSupplierLine> suspectSupplierLines) {

    /**
     * One supplier line on a flagged product whose {@code lastCostPrice} is itself suspect.
     *
     * <p>Reported nested under the product rather than as its own top-level list because a human
     * resolving this works product by product - "what does Rice 50kg actually cost us" is one
     * question, and the answer needs the catalogue figure and every supplier's figure side by
     * side. Splitting them into two reports would make the reader do the join.
     *
     * @param productVendorId the supplier line's own id.
     * @param companyVendorName who it is, for {@link #summary}.
     * @param lastCostPrice as stored, claimed to be per one stock unit.
     * @param medianMovementPrice median {@code unitPriceAtTime} across the {@code IN} movements
     *     from THIS supplier for this product. Null when it has none.
     * @param summary the finding as a sentence. No id.
     */
    public record SuspectSupplierLine(
            UUID productVendorId,
            String companyVendorName,
            BigDecimal lastCostPrice,
            BigDecimal medianMovementPrice,
            String summary) {
    }
}
