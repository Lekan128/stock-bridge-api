package com.procurepal_services.stock_bridge_api.imports.dto;

import java.util.List;

/**
 * The open question about a product sheet's price column (SELLING_PRICE_PLAN.md section 6.8), as the
 * review screen needs it. Absent from {@link ImportSessionResponse} when there is none.
 *
 * @param kind {@code PRICE_MEANING} or {@code KEEP_SELLING_PRICES}.
 * @param header the column, as the file names it (normalized).
 * @param samples up to three of the column's values, so the answer can be checked against the sheet.
 * @param options the answers that make sense now: {@code COST}, {@code SELLING}, {@code IGNORE}. COST
 *     is left out when another column already holds the cost, SELLING when another holds the selling
 *     price or when it would need selling turned on by someone who cannot.
 * @param sellingOn whether the company records sales now.
 * @param canTurnOnSelling whether this person may turn it on (the owner).
 * @param blocking whether the file waits for an answer. A PRICE_MEANING question always blocks; a
 *     KEEP_SELLING_PRICES one only for the person who can turn selling on - anyone else is told.
 */
public record PriceQuestionView(
        String kind,
        String header,
        List<String> samples,
        List<String> options,
        boolean sellingOn,
        boolean canTurnOnSelling,
        boolean blocking) {
}
