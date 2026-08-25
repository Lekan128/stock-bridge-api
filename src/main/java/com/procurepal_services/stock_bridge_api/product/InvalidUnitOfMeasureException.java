package com.procurepal_services.stock_bridge_api.product;

/**
 * A submitted code does not resolve, for the role the caller needed it to be, against the
 * fixed catalog in {@code product.unit.UnitOfMeasure} - see its {@code fromCode} for the
 * lookup this reports the failure of. Maps to 400 with the offending value named, the same
 * treatment InvalidProductVendorException gets.
 *
 * <p>One exception type serves two call sites in {@code ProductManagementService} -
 * {@code resolveUnitOfMeasure} (needs a {@code BASE}-role code) and
 * {@code resolvePackagingUnit} (needs a {@code PACKAGING}-role code) - parameterized by a
 * {@code fieldLabel} rather than split into two classes, so a caller gets a message naming
 * WHICH field rejected the value ("unit of measure" vs "packaging unit") without a second
 * exception type and a second entry in {@code ProductManagementExceptionHandler}. A code that
 * exists on the list but is the WRONG role for the field it was submitted to gets the exact
 * same message as a code that is not on the list at all - from the caller's perspective both
 * are simply "not valid here", and a reader does not need to know the catalog internally
 * tracks role at all to understand the error.
 *
 * <p>Deliberately not thrown for "not on the list yet" in general - {@code fromCode} returns
 * an empty {@link java.util.Optional} for exactly that reason, so a separate request-a-new-unit
 * workflow can exist without this class getting in its way. This exception fires only where a
 * value is actually being written to a product's {@code unitOfMeasure} or {@code packagingUnit}
 * through {@code ProductManagementService}, which is the point at which "not valid here" has to
 * become a decision rather than stay an open question.
 */
public class InvalidUnitOfMeasureException extends RuntimeException {

    /** Convenience for the {@code unitOfMeasure} field - equivalent to {@code (code, "unit of measure")}. */
    public InvalidUnitOfMeasureException(String code) {
        this(code, "unit of measure");
    }

    public InvalidUnitOfMeasureException(String code, String fieldLabel) {
        super("'" + code + "' is not a recognized " + fieldLabel + ".");
    }
}
