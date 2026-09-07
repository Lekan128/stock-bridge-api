package com.procurepal_services.stock_bridge_api.product.sku;

/** The pattern's exact worst-case rendered length exceeds {@link SkuPatternValidator#MAX_RENDERED_LENGTH}. */
public class SkuPatternTooLongException extends RuntimeException {

    public SkuPatternTooLongException(int worstCaseLength, int maxLength) {
        super("This pattern could produce a SKU up to " + worstCaseLength + " characters long, which is more "
                + "than the " + maxLength + "-character limit. Shorten the prefix/suffix text or a token's size.");
    }
}
