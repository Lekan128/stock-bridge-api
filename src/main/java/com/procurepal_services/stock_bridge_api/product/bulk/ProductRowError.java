package com.procurepal_services.stock_bridge_api.product.bulk;

/**
 * row is the Excel row number as the user would see it in their spreadsheet
 * (header = row 1, first data row = row 2), not a zero-based index. row = 0
 * marks a file-level problem (e.g. not a valid .xlsx) that isn't tied to any
 * particular row.
 */
public record ProductRowError(int row, String column, String message) {
}
