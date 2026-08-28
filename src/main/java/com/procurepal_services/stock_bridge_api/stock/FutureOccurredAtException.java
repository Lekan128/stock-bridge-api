package com.procurepal_services.stock_bridge_api.stock;

/**
 * A {@code stockIn} request supplied an {@code occurredAt} in the future - a delivery cannot
 * have arrived on a date that has not happened yet, and a lot dated forward would sort last in
 * FIFO forever, so it would silently never be drawn from while its stock stayed on the books.
 * See {@code StockMovement.occurredAt} and BULK_IMPORT_DESIGN.md section 8.4.
 *
 * <h2>Why the message says "tomorrow" and not "now"</h2>
 * The rule carries a day of grace, and that day is clock and timezone skew, not slack. A
 * {@code received_date} cell is a DATE, not an instant - it arrives as midnight in somebody's
 * timezone - and a user's machine may be minutes or hours ahead of the server. Refusing those
 * would mean telling a user that today is in the future, which is the kind of error message
 * that generates a support ticket rather than a correction. What the rule is actually for is
 * catching a mistyped year, and a day of grace catches that just as well.
 *
 * <p>Mirrors the database's own {@code chk_stock_movements_occurred_at_not_future}, which is the
 * backstop rather than the gate: a CHECK violation surfaces as a 500-shaped constraint error
 * naming a column, and design doc 9.6 is explicit that a column name is never an error subject.
 * This exception exists so the user gets a sentence instead.
 *
 * <p>Maps to 400, the same treatment {@link InvalidStockUnitException} gets.
 */
public class FutureOccurredAtException extends RuntimeException {

    public FutureOccurredAtException() {
        super("A delivery date cannot be in the future.");
    }
}
