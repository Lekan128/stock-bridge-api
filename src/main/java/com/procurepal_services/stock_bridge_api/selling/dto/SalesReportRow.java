package com.procurepal_services.stock_bridge_api.selling.dto;

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
        Long quantity,
        String unitOfMeasure,
        OffsetDateTime occurredAt,
        SalesTotals totals,
        String detail) {
}
