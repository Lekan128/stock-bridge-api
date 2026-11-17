package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.selling.SaleCancelReason;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * @param lineIds the lines to cancel; absent or empty cancels the whole sale.
 * @param reason why - the four pictures at the counter (selling till Step 3). Kept on the sale when the whole
 *     sale is cancelled.
 * @param note a few words more, optional.
 */
public record CancelSaleRequest(List<UUID> lineIds, SaleCancelReason reason, @Size(max = 300) String note) {

    /** The shape before Step 3's reason. */
    public CancelSaleRequest(List<UUID> lineIds) {
        this(lineIds, null, null);
    }
}
