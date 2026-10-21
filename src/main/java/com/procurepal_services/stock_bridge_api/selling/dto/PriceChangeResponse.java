package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One change to a product's selling price, for its history: "Price changed from ₦5,000 to ₦5,200 a
 * carton by Ada, 3 Oct". The entered figures are what was typed and per what; the unit prices are
 * the per-stock-unit figures behind them.
 */
public record PriceChangeResponse(
        BigDecimal oldUnitPrice,
        BigDecimal newUnitPrice,
        BigDecimal oldEnteredPrice,
        String oldEnteredUnit,
        BigDecimal newEnteredPrice,
        String newEnteredUnit,
        ProductPriceChange.Source source,
        String changedByName,
        OffsetDateTime changedAt) {
}
