package com.procurepal_services.stock_bridge_api.entity;

/**
 * Mirrors stock_movements.movement_type's check constraint.
 *
 * <p>TRANSFER_OUT and TRANSFER_IN (V53) are stock moving between a company's branches: out of one
 * shelf, into another, the company's total unchanged once it has arrived. A reader of this type gives
 * them a sign on purpose (MULTI_BRANCH_BUILD_PLAN.md section 4.3); one that only knows IN and OUT
 * leaves them out, which is right for anything about deliveries, sales and their money.
 */
public enum MovementType {
    IN,
    OUT,
    ADJUSTMENT,
    /** Stock leaving a branch on a waybill (V53). Draws on that branch's lots, like a stock-out. */
    TRANSFER_OUT,
    /** Stock arriving at a branch off a waybill (V53). A lot of its own, carrying its delivery's cost. */
    TRANSFER_IN
}
