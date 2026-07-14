package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;

/** A single spreadsheet row that passed column-level validation. excelRow is 1-based, matching what the user sees. */
public record ParsedProductRow(
        int excelRow,
        String name,
        String sku,
        String description,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        int quantityOnHand,
        Integer lowStockThreshold) {
}
