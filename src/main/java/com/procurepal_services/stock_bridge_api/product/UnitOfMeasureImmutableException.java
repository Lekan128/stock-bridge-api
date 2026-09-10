package com.procurepal_services.stock_bridge_api.product;

/**
 * A request tried to change {@code Product.unitOfMeasure} on a product that already has at
 * least one {@code StockMovement}. Every historical quantity on that product is implicitly
 * recorded in the unit it had at the time; changing it now would silently reinterpret every
 * past movement rather than converting anything - see {@code Product.unitOfMeasure}'s javadoc
 * for the full reasoning (V19, MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3).
 *
 * <p>Maps to 400, the same treatment every other product validation failure in this package
 * gets. {@code packagingUnit}/{@code packagingSize} are NOT covered by this rule and stay
 * editable any time - only a change to {@code unitOfMeasure} itself triggers this.
 */
public class UnitOfMeasureImmutableException extends RuntimeException {

    public UnitOfMeasureImmutableException() {
        super("unitOfMeasure cannot be changed once this product has recorded stock movements.");
    }
}
