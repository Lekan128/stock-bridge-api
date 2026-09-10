package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.stock.dto.ProductLotResponse;
import java.time.OffsetDateTime;

/**
 * A {@code stockOut} request's explicit {@code allocations} list (the "choose which deliveries
 * this comes from" path - MULTI_VENDOR_INVENTORY_DESIGN.md section 6/8) named a delivery that
 * either is not this product's, or does not have enough left in it, or the lines do not add up
 * to the quantity being taken out. Distinct from {@link InsufficientStockException}, which is
 * about the TOTAL exceeding everything available across every lot - this is about one line of
 * the request being wrong.
 *
 * <h2>A delivery is named by its date and supplier, never by its id</h2>
 * UNIT_UX_REMEDIATION_PLAN.md section 3, P1-6: the old messages read <em>"Only 12 remaining from
 * that delivery (a3f9…-…), 20 requested."</em> That is a UUID in a sentence a user reads, which
 * both {@code BULK_IMPORT_CONTRACT.md} section 8.7 and UNIT_UX_CONTRACT.md non-negotiable 6
 * forbid in as many words - and it is also simply unusable, because the id is not on the screen
 * the user is looking at. The lot's own {@link ProductLotResponse#label} is, so that is what
 * every factory below names it by. Same composer, so the sentence and the row the user clicked
 * always agree about which delivery is meant.
 *
 * <p>Every quantity in these messages carries its unit, and every one of them is in the
 * product's <b>stock unit</b> - which is also what {@code StockOutRequest.allocations[].quantity}
 * is measured in (contract section 4). Stating it removes the P1-4 trap where the client
 * validated a total in bags against a server that validated it in kg.
 *
 * <p>Maps to 400.
 */
public class InvalidStockAllocationException extends RuntimeException {

    public InvalidStockAllocationException(String message) {
        super(message);
    }

    /**
     * The referenced delivery is not one of this product's. No id in the message and none
     * needed: a user cannot act on an id they never saw, and the only honest thing to say is
     * that the line refers to something that is not on this product's shelf.
     */
    public static InvalidStockAllocationException notThisProductsDelivery() {
        return new InvalidStockAllocationException(
                "One of the deliveries you chose is not a delivery of this product — reopen the list of deliveries "
                        + "and choose again.");
    }

    /**
     * <em>"Only 12 kg left from the 3 Jan 2026 delivery from Dangote Nigeria Plc — you asked for
     * 20 kg."</em> - UNIT_UX_CONTRACT.md section 4's pinned message.
     *
     * <p>The year is present where the contract's example omitted it; see
     * {@link ProductLotResponse#labelDate} for why (a picker of open lots routinely spans a year
     * boundary, and "3 Jan" alone does not say which January).
     *
     * @param remainingBaseUnits what is genuinely left in that lot, in the product's stock unit.
     * @param requestedBaseUnits what this line asked for, same unit.
     * @param unitSymbol the stock unit's short symbol ("kg"); "units" when the product has none.
     * @param occurredAt when that delivery arrived - the date the user sees on its row.
     * @param companyVendorName who it came from, or null for a lot with no supplier on file.
     */
    public static InvalidStockAllocationException notEnoughInLot(
            int remainingBaseUnits,
            int requestedBaseUnits,
            String unitSymbol,
            OffsetDateTime occurredAt,
            String companyVendorName) {
        String unit = unitSymbol == null || unitSymbol.isBlank() ? "units" : unitSymbol;
        String from = companyVendorName == null || companyVendorName.isBlank()
                ? ""
                : " from " + companyVendorName;
        return new InvalidStockAllocationException("Only " + remainingBaseUnits + " " + unit + " left from the "
                + ProductLotResponse.labelDate(occurredAt) + " delivery" + from + " — you asked for "
                + requestedBaseUnits + " " + unit + ".");
    }

    /**
     * The chosen deliveries do not add up to what is being taken out.
     *
     * <p>Both figures are in the product's stock unit, and the message says so, because P1-4 was
     * precisely this sentence read in two different units: the modal compared the same two
     * numbers in the unit the user had toggled to, passed, and then the server compared them in
     * base units and refused. Whichever side is wrong, the user can only fix it if the message
     * states what the numbers are counted in.
     */
    public static InvalidStockAllocationException totalMismatch(
            int allocatedBaseUnits, int requestedBaseUnits, String unitSymbol) {
        String unit = unitSymbol == null || unitSymbol.isBlank() ? "units" : unitSymbol;
        return new InvalidStockAllocationException("The deliveries you chose add up to " + allocatedBaseUnits + " "
                + unit + ", but you are taking out " + requestedBaseUnits + " " + unit
                + " — adjust them until the two match.");
    }
}
