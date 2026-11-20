package com.procurepal_services.stock_bridge_api.selling;

/** Why a sale was cancelled - the four pictures at the counter (selling till Step 3). */
public enum SaleCancelReason {
    WRONG_ITEM,
    WRONG_AMOUNT,
    CHANGED_MIND,
    OTHER
}
