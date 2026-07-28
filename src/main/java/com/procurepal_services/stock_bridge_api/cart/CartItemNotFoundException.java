package com.procurepal_services.stock_bridge_api.cart;

/** The company's cart holds no line for that product - it was never added, or a colleague removed it. */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException() {
        super("That item is no longer in your cart.");
    }
}
