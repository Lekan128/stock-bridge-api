package com.procurepal_services.stock_bridge_api.entity;

/**
 * Which path a company setting was changed through - {@code client_setting_changes.source} (V45).
 * Read straight off by SELLING_PRICE_PLAN.md section 9's "how did they find it" measure.
 */
public enum SettingChangeSource {
    /** The owner's own settings page. */
    OWNER,
    /** ProcurePal support, on the owner's behalf. Always carries a reason. */
    SUPPORT,
    /** The first-week question, "What happens to your stock?". */
    FIRST_WEEK,
    /** An offer made in context - picking "Sold" on a stock-out, a sheet with selling prices. */
    IN_CONTEXT
}
