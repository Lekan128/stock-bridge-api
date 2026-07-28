package com.procurepal_services.stock_bridge_api.marketplace.analytics;

/**
 * What "top" means for a product. The two disagree constantly and both are worth having:
 * one pallet of generator sets outranks a thousand sachets on REVENUE and loses badly on
 * QUANTITY, and only the second view tells the warehouse what to restock.
 */
public enum ProductRankMetric {
    REVENUE,
    QUANTITY
}
