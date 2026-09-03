package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.util.List;

/**
 * A {@code stockIn}/{@code stockOut} request's {@code unit} cannot be turned into a quantity in
 * the product's stock unit - either because it is not one of the units this product is counted
 * in, or because converting it would round the delivery away to nothing.
 *
 * <h2>The message is the feature</h2>
 * UNIT_UX_REMEDIATION_PLAN.md section 3, P1-1 records the old message - <em>"'CARTON' is not a
 * unit this product is configured with"</em> - as the reported complaint itself. It is true, it
 * names no column and leaks no id, and it is still useless: it tells a user that the answer they
 * gave is wrong without telling them what the right answers are, on a screen that had just
 * offered them thirty codes of which two would work. UNIT_UX_CONTRACT.md section 3.1 replaces it
 * with a message that names <b>every valid option</b>, which is contract non-negotiable 4's rule
 * ("never ask a question whose valid answers you do not show") applied to an error.
 *
 * <p>Both factories below compose their sentence from {@link UnitOptions}, the same source the
 * picker and the spreadsheet's reference column read, so the error can never list a set the UI
 * did not offer. Neither ever contains a UUID or a column name - non-negotiable 6, and section
 * 4's rule for this class specifically.
 *
 * <p>Maps to 400, the same treatment {@code CompanyVendorRequiredException} gets: the request as
 * written is not a thing that can have happened, and it would have been just as wrong yesterday.
 */
public class InvalidStockUnitException extends RuntimeException {

    public InvalidStockUnitException(String message) {
        super(message);
    }

    /**
     * <em>"Rice 50kg is counted in kg or bags of 50 kg — we don't know how to count it in
     * cartons."</em> - UNIT_UX_CONTRACT.md section 3.1's first message, verbatim in shape.
     *
     * <p>The product is named, not the field: a user reading this is looking at a form with one
     * quantity box, and "unit" as a subject would be telling them the name of one of our columns.
     * The rejected unit is echoed as a word ("cartons"), or quoted when it is not a unit we know
     * at all, so a typo comes back looking like a typo rather than like something we understood.
     *
     * @param productName the product the delivery is for - the sentence's subject.
     * @param submittedUnit exactly what the request's {@code unit} said.
     * @param options this product's unit set, INCLUDING any per-request pack override, so the
     *     message lists what would actually have been accepted for this request rather than what
     *     is accepted in general.
     */
    public static InvalidStockUnitException unknownUnit(
            String productName, String submittedUnit, List<UnitOption> options) {
        return new InvalidStockUnitException(productName + " is counted in " + UnitOptions.countedInPhrase(options)
                + " — we don't know how to count it in " + UnitOptions.spokenPhraseOfSubmitted(submittedUnit) + ".");
    }

    /**
     * <em>"1 g is less than one whole kg — enter this in g by changing this product's stock unit,
     * or enter a larger amount."</em> - UNIT_UX_CONTRACT.md section 3.1's second message.
     *
     * <h2>Why this is an error and not a zero</h2>
     * Every stock quantity in this schema is an integer in the product's stock unit, so 1 g on a
     * product counted in kg has nowhere to go: {@code round(1 × 0.001)} is 0. Recording that
     * would mean writing "this delivery contained nothing" for a delivery somebody just typed,
     * and then quietly blending its price into a weighted average over zero units. Section 3.1 is
     * explicit - "a conversion that rounds to zero ⇒ 400, never a silent 0".
     *
     * <p>The message offers both real ways out, because they are genuinely different decisions:
     * change how the product is counted (a lasting configuration change, right when the user has
     * just discovered they need it), or enter an amount that is at least one whole stock unit.
     *
     * @param enteredQuantity the number the user typed, in {@code enteredOption}'s unit.
     * @param enteredOption the unit they typed it in - the one whose factor is too small.
     * @param stockUnitSymbol the product's stock unit, short form ("kg").
     */
    public static InvalidStockUnitException roundsToZero(
            int enteredQuantity, UnitOption enteredOption, String stockUnitSymbol) {
        String entered = UnitOptions.spokenPhrase(enteredOption);
        return new InvalidStockUnitException(enteredQuantity + " " + entered + " is less than one whole "
                + stockUnitSymbol + " — enter this in " + entered
                + " by changing this product's stock unit, or enter a larger amount.");
    }
}
