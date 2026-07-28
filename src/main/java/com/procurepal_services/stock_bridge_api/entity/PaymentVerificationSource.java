package com.procurepal_services.stock_bridge_api.entity;

/**
 * Which of the three independent paths told us a payment succeeded
 * (payments.verified_via). All three exist because none of them is reliable
 * alone: a webhook can be dropped, a buyer can close the tab before the return
 * redirect fires, and only the sweep catches an order stranded by both.
 *
 * Recorded for operational insight - a spike in RECONCILIATION means webhook
 * delivery is broken, which is otherwise invisible because the orders still end
 * up paid.
 */
public enum PaymentVerificationSource {

    WEBHOOK,
    /** Server-side re-verify triggered when the browser comes back from checkout. */
    RETURN_VERIFY,
    /** The scheduled sweep over payments still PENDING past their grace period. */
    RECONCILIATION
}
