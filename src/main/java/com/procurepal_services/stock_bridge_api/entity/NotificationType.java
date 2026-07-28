package com.procurepal_services.stock_bridge_api.entity;

/**
 * What a notification is about. Kept coarse on purpose - the type drives an icon
 * and a colour, while the human-readable detail lives in the notification's own
 * title/body, so adding a new wording never needs a new enum value (or a
 * migration to widen the CHECK constraint).
 */
public enum NotificationType {

    /** To ProcurePal: a company just placed an order. */
    NEW_ORDER,
    ORDER_STATUS_CHANGED,
    PAYMENT_RECEIVED,
    PAYMENT_FAILED,
    ORDER_DELIVERED
}
