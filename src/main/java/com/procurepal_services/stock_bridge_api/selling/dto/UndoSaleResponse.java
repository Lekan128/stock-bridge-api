package com.procurepal_services.stock_bridge_api.selling.dto;

import java.util.List;
import java.util.UUID;

/**
 * Undo sale, line by line: each line is undone on its own (D8's rules - same person, two minutes,
 * still the product's latest write), so one line that can no longer be undone does not stop the
 * others. A line that was not undone says why, in a sentence the seller reads.
 */
public record UndoSaleResponse(UUID saleId, List<Line> lines, int undoneCount, int notUndoneCount) {

    public record Line(UUID movementId, String productName, boolean undone, String reason) {
    }
}
