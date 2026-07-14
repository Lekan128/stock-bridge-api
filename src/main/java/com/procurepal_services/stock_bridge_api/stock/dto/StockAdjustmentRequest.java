package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StockAdjustmentRequest(@NotNull @Min(0) Integer newQuantity, @NotBlank @Size(max = 1000) String note) {
}
