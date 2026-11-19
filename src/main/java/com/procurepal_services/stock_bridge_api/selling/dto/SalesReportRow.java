package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of the Sales report: a sale, a product, a day, a person or a category, with its totals.
 *
 * @param key what the row is grouped on - a sale id, product id, ISO day, user id or category id
 *     (absent for "No category" and for sales recorded with no person).
 * @param label how the row reads: the product's or person's name, the category, the day.
 * @param quantity for product rows only: how much went, in {@code unitOfMeasure} - adding kilograms
 *     to litres across products would mean nothing.
 * @param occurredAt for sale rows only: when the sale happened.
 * @param detail for sale rows only: the products in it, by name ("Rice, Soap").
 */
public record SalesReportRow(
        String key,
        String label,
        BigDecimal quantity,
        String unitOfMeasure,
        OffsetDateTime occurredAt,
        SalesTotals totals,
        String detail,
        /** The branch a sale was recorded at (V51): on the "sale" grouping, at a multi-branch company. */
        String branch,
        /** How a sale was paid (selling till Step 1): on the "sale" grouping; absent for one from before. */
        String paymentMethod,
        /** Sale rows (selling till Step 3): REQUESTED, DECLINED or CANCELLED; absent when nobody asked or cancelled. */
        String cancelState,
        /** WRONG_ITEM, WRONG_AMOUNT, CHANGED_MIND or OTHER. */
        String cancelReason,
        /** Who cancelled it, or declined the request. */
        String cancelledByName,
        /** Who asked for it to be cancelled. */
        String cancelRequestedByName,
        /** Step 12 (V96): the account a transfer sale came into ("GTBank"); absent otherwise. */
        String paymentAccountName,
        /**
         * Step 10 (V100), sale rows: the charges and taxes added on top - what the customer paid is the total plus this.
         * Absent when there were none, or when the list is narrowed to some of each sale (a product, an area).
         */
        BigDecimal chargesOnTop,
        /**
         * Sale rows (owner, 2026-11-19): the charges and taxes that were in its prices. The row's total leaves them
         * out, so what the customer paid is the total plus both.
         */
        BigDecimal chargesInPrices) {

    public SalesReportRow(String key, String label, BigDecimal quantity, String unitOfMeasure, OffsetDateTime occurredAt,
            SalesTotals totals, String detail, String branch, String paymentMethod, String cancelState, String cancelReason,
            String cancelledByName, String cancelRequestedByName) {
        this(key, label, quantity, unitOfMeasure, occurredAt, totals, detail, branch, paymentMethod, cancelState,
                cancelReason, cancelledByName, cancelRequestedByName, null, null, null);
    }

    /** The same row with nothing about cost - for a cashier's own list. */
    public SalesReportRow withoutCost() {
        return new SalesReportRow(key, label, quantity, unitOfMeasure, occurredAt, totals.withoutCost(), detail, branch,
                paymentMethod, cancelState, cancelReason, cancelledByName, cancelRequestedByName, paymentAccountName, chargesOnTop, chargesInPrices);
    }
}
