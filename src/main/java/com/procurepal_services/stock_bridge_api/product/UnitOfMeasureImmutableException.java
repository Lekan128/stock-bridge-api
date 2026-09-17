package com.procurepal_services.stock_bridge_api.product;

/**
 * A request tried to change {@code Product.unitOfMeasure} on a product that already has at
 * least one {@code StockMovement}. Every historical quantity on that product is implicitly
 * recorded in the unit it had at the time; changing it now would silently reinterpret every
 * past movement rather than converting anything - see {@code Product.unitOfMeasure}'s javadoc
 * for the full reasoning (V19, MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3).
 *
 * <p>Maps to 409: the request is fine in itself and conflicts with the product's recorded stock.
 * (It had no handler at all and surfaced as a 500 until BULK_IMPORT_CX_PLAN.md task 1.8.) A
 * product with no unit yet may still be given its first one. {@code packagingUnit}/{@code packagingSize} are NOT covered by this rule and stay
 * editable any time - only a change to {@code unitOfMeasure} itself triggers this.
 */
public class UnitOfMeasureImmutableException extends RuntimeException {

    public UnitOfMeasureImmutableException() {
        super("This product's unit can't be changed now that stock has been recorded in it - everything already recorded is counted that way. Add a new product if you now count it differently.");
    }
}
