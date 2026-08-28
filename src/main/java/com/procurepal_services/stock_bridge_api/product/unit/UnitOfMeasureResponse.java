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
 */
public record UnitOfMeasureResponse(String code, String label, UnitOfMeasureCategory category, UnitOfMeasureRole role) {

    public static UnitOfMeasureResponse from(UnitOfMeasure unit) {
        return new UnitOfMeasureResponse(unit.code(), unit.label(), unit.category(), unit.role());
    }
}
