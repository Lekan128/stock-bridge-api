package com.procurepal_services.stock_bridge_api.stock.dto;

import java.math.BigDecimal;
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
        @NotNull @Min(0) BigDecimal newQuantity,
        @NotBlank @Size(max = 1000) String note,
        UUID branchId,
        // Areas inside a branch (V60): see StockInRequest.areaId.
        UUID areaId) {

    /** The shape before {@code areaId} (areas, Part 0). */
    public StockAdjustmentRequest(BigDecimal newQuantity, String note, UUID branchId) {
        this(newQuantity, note, branchId, null);
    }

    /** The shape before {@code branchId} (multiple branches, Phase 0). */
    public StockAdjustmentRequest(BigDecimal newQuantity, String note) {
        this(newQuantity, note, null, null);
    }

    /** This adjustment, at {@code branch} - how a caller states the branch it has resolved. */
    public StockAdjustmentRequest withBranchId(UUID branch) {
        return new StockAdjustmentRequest(newQuantity, note, branch, areaId);
    }
}
