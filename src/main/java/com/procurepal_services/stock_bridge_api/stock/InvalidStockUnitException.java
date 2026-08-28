package com.procurepal_services.stock_bridge_api.stock;

/**
 * A {@code stockIn}/{@code stockOut} request's {@code unit} names something other than the
 * product's base {@code unitOfMeasure} or its configured packaging unit - the only two units
 * {@code StockManagementService} knows how to convert a quantity from. Also thrown when
 * {@code unit} correctly names the packaging unit but there is no {@code packagingSize} to
 * convert with (neither on the request nor on the product itself).
 *
 * <p>Maps to 400, the same treatment {@code InsufficientStockException} gets.
 */
public class InvalidStockUnitException extends RuntimeException {

    public InvalidStockUnitException(String unit) {
        super("'" + unit + "' is not a unit this product is configured with.");
    }
}
