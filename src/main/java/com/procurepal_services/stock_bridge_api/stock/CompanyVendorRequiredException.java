package com.procurepal_services.stock_bridge_api.stock;

/**
 * A {@code stockIn} request omitted {@code companyVendorId} for a product that already has at
 * least one {@link com.procurepal_services.stock_bridge_api.entity.ProductVendor} line on
 * file. Required from that point on - see MULTI_VENDOR_INVENTORY_DESIGN.md section 8: "add
 * companyVendorId (required once product has ≥1 vendor)". A product with zero vendors may
 * still receive stock without one; the very first stock-in with a vendor supplied is what
 * creates that first line.
 *
 * <p>Maps to 400, the same treatment {@code InsufficientStockException} gets from
 * {@code StockManagementExceptionHandler}.
 */
public class CompanyVendorRequiredException extends RuntimeException {

    public CompanyVendorRequiredException() {
        super("companyVendorId is required once a product has at least one vendor on file.");
    }
}
