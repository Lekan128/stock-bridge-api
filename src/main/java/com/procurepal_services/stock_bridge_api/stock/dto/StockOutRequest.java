package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record StockOutRequest(
        @NotNull @Positive Integer quantity,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @Size(max = 1000) String note) {
}
