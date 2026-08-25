package com.procurepal_services.stock_bridge_api.product.unit;

/**
 * The four groupings {@link UnitOfMeasure} is organised into, modelled on how
 * Amazon/Jumia/Odoo group their own unit pickers - by what kind of quantity the
 * unit measures, not by industry. A frontend picker uses this to render section
 * headers without hardcoding a second copy of the grouping.
 */
public enum UnitOfMeasureCategory {
    /** Discrete, countable units - Piece, Box, Dozen, and similar. */
    COUNT,
    /** Mass - Gram, Kilogram, and similar. */
    WEIGHT,
    /** Liquid/fill volume - Milliliter, Liter. */
    VOLUME,
    /** Linear measure - Millimeter, Centimeter, Meter. */
    LENGTH
}
