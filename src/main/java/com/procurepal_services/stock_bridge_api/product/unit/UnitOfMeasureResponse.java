package com.procurepal_services.stock_bridge_api.product.unit;

/**
 * Wire shape for {@code GET /api/products/units-of-measure} - one row per
 * {@link UnitOfMeasure} constant. Flat with a {@code category} field rather
 * than pre-grouped: grouping four categories client-side is a one-line
 * {@code groupBy}, and a flat list is also what a simple code-to-label select
 * box wants without first flattening a nested shape back out.
 *
 * <p>{@code role} tells the frontend which of the TWO pickers a code belongs in - the
 * {@code unitOfMeasure} dropdown ({@link UnitOfMeasureRole#BASE}) or the {@code packagingUnit}
 * one ({@link UnitOfMeasureRole#PACKAGING}) - so a client can filter this same flat list into
 * both without hardcoding a second copy of which codes go where. See
 * {@link UnitOfMeasureRole} for why a product needs both in the first place.
 *
 * <h2>{@code symbol} and {@code factorToCanonical} - what the catalog picker also has to say</h2>
 * Both added with UNIT_UX_CONTRACT.md section 2.2, and both are here because this endpoint is
 * the ONLY place a client learns about units it has not yet seen on a product.
 *
 * <p>{@code symbol} is the short form a number is written with ("kg", "Piece"), so a form can
 * render a live unit suffix beside the stock-unit select it is filtering out of this same list -
 * "50 kg per bag" - without keeping a second copy of which label abbreviates to what. That
 * duplicated table is how the four vocabularies in UNIT_UX_REMEDIATION_PLAN.md section 2 grew.
 *
 * <p>{@code factorToCanonical} is null for every PACKAGING-role constant, and a client should
 * read that null as the operative fact rather than as missing data: a unit with no factor is not
 * an alternative unit, it is a container whose size is a property of a product. It is published
 * so a client can tell at a glance which BASE codes are inter-convertible within a category,
 * matching the per-product factors it receives in {@code unitOptions}. It is NOT a licence to
 * derive a product's unit set client-side - that set is server-derived, closed, and arrives on
 * the product (contract section 2.3); non-negotiable 1 is only enforceable while there is one
 * implementation of it.
 */
public record UnitOfMeasureResponse(
        String code,
        String label,
        String symbol,
        UnitOfMeasureCategory category,
        UnitOfMeasureRole role,
        java.math.BigDecimal factorToCanonical,
        boolean canBeStockUnit,
        boolean canBePack) {

    /**
     * {@code canBeStockUnit}/{@code canBePack} answer what every picker actually needs, so no
     * client re-derives it.
     *
     * <h2>Why {@code role} alone is not enough on the wire</h2>
     * {@code role} is the unit's DECLARED role; since COUNT units may serve either (see
     * {@link UnitOfMeasure#canServeAs}), a client filtering on {@code role == BASE} would hide
     * "Piece" from the pack picker and a turmeric sold in 34 g pieces could not be described. The
     * rule is one line, which is exactly why it must not be copied: one line replicated in a
     * product form, a spreadsheet dropdown and an import grid is three places to forget it. The
     * server answers the question instead of publishing the inputs to it.
     */
    public static UnitOfMeasureResponse from(UnitOfMeasure unit) {
        return new UnitOfMeasureResponse(
                unit.code(),
                unit.label(),
                unit.symbol(),
                unit.category(),
                unit.role(),
                unit.factorToCanonical(),
                unit.canServeAs(UnitOfMeasureRole.BASE),
                unit.canServeAs(UnitOfMeasureRole.PACKAGING));
    }
}
