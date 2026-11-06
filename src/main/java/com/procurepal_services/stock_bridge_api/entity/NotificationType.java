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
    ORDER_DELIVERED,
    /** ProcurePal support changed one of the company's settings (V45) - the owner is told who and why. */
    ACCOUNT_SETTING_CHANGED,
    /** To the people at a branch: stock is on its way to them (V53, MULTI_BRANCH_UX_PLAN.md section 5.13). */
    TRANSFER_ON_THE_WAY,
    /** To the sending branch and the owners: a waybill arrived short (V53). */
    TRANSFER_RECEIVED_SHORT,
    /** To the people of an area that confirms: a move is waiting for them (V66, STOCK_AREAS_PART_4.md). */
    AREA_MOVE_WAITING,
    /** To the mover and the owners: an area confirmed a move short (V66). */
    AREA_MOVE_SHORT,
    /** To the people of the area asked: another area asked it for stock (V67, STOCK_AREAS_PART_5.md). */
    STOCK_REQUEST,
    /** To the asker: what they asked for was moved, or there's none in the store (V67). */
    STOCK_REQUEST_ANSWERED,
    /** To the area a give came from and the owners: an area that uses stock up gave some back or reported it (V70). */
    AREA_RETURN,
    /** To the people who can approve: a purchase order is waiting for them (V78, plan section 11.5). */
    PO_APPROVAL_WAITING,
    /** To whoever sent it for approval: approved, or a change was asked for (V78). */
    PO_APPROVAL_ANSWERED,
    /** To whoever asked and those who can send: a supplier sent, changed or declined their prices (V80). */
    PRICE_REPLY
}
