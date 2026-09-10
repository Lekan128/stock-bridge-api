package com.procurepal_services.stock_bridge_api.product;

/**
 * {@code packagingSize} is a COUNT of {@code unitOfMeasure} - "a 50kg bag" only makes sense
 * once you know the bag holds 50 of something, and that something is {@code unitOfMeasure}.
 * So a product may never carry {@code packagingUnit}/{@code packagingSize} without also
 * carrying a {@code unitOfMeasure} to quantify - unlike the {@code packagingUnit}/
 * {@code packagingSize} pairing itself (see {@link PackagingUnitAndSizeRequiredTogetherException}),
 * which is symmetric, this rule is one-directional: {@code unitOfMeasure} may always be set
 * alone (a product sold loose, e.g. {@code unitOfMeasure="LITER"} with no packaging at all),
 * but packaging may never be set alone.
 *
 * <p>Maps to 400, the same treatment every other product validation failure in this package
 * gets. Thrown by {@code ProductManagementService.create} and {@code .update} after applying
 * whichever fields a request supplied, checked against the RESULTING state of the product -
 * same patch-safe timing as {@link PackagingUnitAndSizeRequiredTogetherException} - so an
 * update that supplies only packaging fields is fine as long as the product already carries a
 * {@code unitOfMeasure} from before, and a patch that clears {@code unitOfMeasure} while
 * leaving packaging in place is rejected exactly as if they had never been set together.
 */
public class PackagingRequiresUnitOfMeasureException extends RuntimeException {

    public PackagingRequiresUnitOfMeasureException() {
        super("packagingUnit/packagingSize require unitOfMeasure to also be set.");
    }
}
