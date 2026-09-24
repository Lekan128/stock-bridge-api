package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where an {@link ExpectedDelivery} stands. Three states and no more: a partial receipt is
 * expressed by each line's {@code receivedQuantity}, not by a fourth status, because "partly
 * arrived" is a fact about the lines and inventing a status for it would leave two places to ask
 * the same question and two chances to disagree.
 */
public enum ExpectedDeliveryStatus {
    /** Still waiting, whether nothing or only some of it has arrived. */
    OPEN,
    /** Every line has had at least what was expected of it received. Closed by the commit. */
    RECEIVED,
    /** Called off. It never arrived and is not coming; nothing about stock changes. */
    CANCELLED
}
