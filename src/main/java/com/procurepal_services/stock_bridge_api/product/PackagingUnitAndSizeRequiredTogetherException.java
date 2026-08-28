package com.procurepal_services.stock_bridge_api.product;

/**
 * {@code packagingUnit} and {@code packagingSize} describe one fact together - how much one
 * packaging unit actually holds, e.g. {@code packagingUnit="BAG"} and
 * {@code packagingSize=50} meaning "a 50kg bag" (paired with {@code unitOfMeasure="KG"} - see
 * {@link PackagingRequiresUnitOfMeasureException} for that third leg) - so a product ending up
 * with exactly one of the two set is ambiguous rather than partially valid: a size with no
 * packaging unit cannot be read at all, and a packaging unit with no size says nothing about
 * how much one of it is. Both unset is fine - the pair itself is optional, e.g. a product
 * measured but sold loose ({@code unitOfMeasure} alone, no packaging).
 *
 * <p>Renamed from {@code UnitOfMeasureAndCountRequiredTogetherException} when
 * {@code unitOfMeasure}/{@code unitCount} split into three fields: the pairing rule this class
 * enforces now applies to {@code packagingUnit}/{@code packagingSize} specifically, not to
 * {@code unitOfMeasure} (which has its own, different rule - see
 * {@link PackagingRequiresUnitOfMeasureException}).
 *
 * <p>Maps to 400, the same treatment every other product validation failure in this package
 * gets. Thrown by {@code ProductManagementService.create} and {@code .update} after applying
 * whichever of the pair a request supplied, checked against the RESULTING state of the product
 * so patch-style updates (only one of the two sent, the other already set from before) are not
 * flagged as incomplete.
 */
public class PackagingUnitAndSizeRequiredTogetherException extends RuntimeException {

    public PackagingUnitAndSizeRequiredTogetherException() {
        super("packagingUnit and packagingSize must be provided together, or not at all.");
    }
}
