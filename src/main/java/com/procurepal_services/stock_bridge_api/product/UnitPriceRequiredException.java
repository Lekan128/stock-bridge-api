package com.procurepal_services.stock_bridge_api.product;

/**
 * A marketplace seller's product - a {@link com.procurepal_services.stock_bridge_api.entity.ClientType#VENDOR},
 * or the platform owner acting as one, per {@link com.procurepal_services.stock_bridge_api.entity.Client#canSell()} -
 * has no selling price. Unlike an ordinary buying company's private stock, a seller's row IS a
 * listing: the price is the entire reason a buyer would look at it, so unlike a non-seller's
 * product (which never requires one at all) this cannot be left blank. Maps to 400, the same
 * treatment InvalidProductVendorException gets.
 *
 * <p>Thrown two ways from {@code ProductManagementService}: creating a seller's product with no
 * {@code unitPrice}, or updating one such that it is LEFT with none - either because the
 * request explicitly has nothing and the row already had none, or because the row already had
 * none and this patch-style update did not supply a replacement either.
 */
public class UnitPriceRequiredException extends RuntimeException {

    public UnitPriceRequiredException() {
        super("Unit price is required for a marketplace seller's product.");
    }
}
