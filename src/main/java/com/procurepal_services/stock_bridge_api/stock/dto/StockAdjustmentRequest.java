package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param branchId where the shelf is (V51); absent means the company's only branch - see
 *     {@code StockInRequest.branchId}.
 */
public record StockAdjustmentRequest(
        @NotNull @Min(0) Integer newQuantity, @NotBlank @Size(max = 1000) String note, UUID branchId) {

    /** The shape before {@code branchId} (multiple branches, Phase 0). */
    public StockAdjustmentRequest(Integer newQuantity, String note) {
        this(newQuantity, note, null);
    }
}
