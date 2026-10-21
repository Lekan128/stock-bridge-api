package com.procurepal_services.stock_bridge_api.entity;

/** What a company does with its stock - {@code clients.stock_use} (V49), the first-week answer. */
public enum StockUse {
    /** "We use it to make or serve things." */
    USE,
    /** "We sell it as it is." */
    SELL,
    /** Both: sells some, uses some - a bakery selling bread and using flour. */
    BOTH
}
