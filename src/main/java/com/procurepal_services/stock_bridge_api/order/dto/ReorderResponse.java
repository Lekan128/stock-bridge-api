package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.cart.dto.CartResponse;
import java.util.List;
import java.util.UUID;

/**
 * Reorder is best-effort by design. A six-month-old order will routinely contain
 * something ProcurePal has since discontinued or sold out of, and failing the whole
 * basket over one line would make the feature useless exactly when it is most
 * wanted. The skipped lines come back named, so the UI can say what did not make it
 * rather than leaving the buyer to diff two carts by eye.
 */
public record ReorderResponse(CartResponse cart, int addedCount, List<SkippedLine> skipped) {

    public record SkippedLine(UUID productId, String productName, String reason) {
    }
}
