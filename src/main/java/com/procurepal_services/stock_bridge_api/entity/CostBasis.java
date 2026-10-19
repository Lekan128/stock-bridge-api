package com.procurepal_services.stock_bridge_api.entity;

/**
 * How a stock-out's {@code cost_amount} was arrived at (V46). The report says "profit leaves out N
 * sales" from {@link #UNKNOWN}; the other two are both real costs.
 */
public enum CostBasis {
    /** Every unit costed from the delivery it actually came from. */
    FIFO,
    /** Some units costed at the product's average cost - a delivery with no price, or older stock. */
    AVERAGE,
    /** No cost could be known; {@code cost_amount} is null. */
    UNKNOWN
}
