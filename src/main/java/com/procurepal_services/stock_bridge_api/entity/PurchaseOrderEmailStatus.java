package com.procurepal_services.stock_bridge_api.entity;

/** What became of one attempt to email a purchase order. */
public enum PurchaseOrderEmailStatus {
    /** The email provider accepted it. Said as "Emailed" - we do not claim it was read. */
    SENT,
    /** It never left: nothing set up, the buyer unconfirmed, a cap, an address that cannot be used. */
    FAILED,
    /** The provider said afterwards that the address does not exist or refuses mail. */
    BOUNCED,
    /** The recipient marked it as unwanted. */
    COMPLAINED
}
