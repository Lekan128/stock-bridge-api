package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import java.util.List;

/**
 * Everything the vendor detail screen shows, in one round trip: the directory row,
 * the live seller behind it (VERIFIED only), what has been spent with them, and
 * what they supply at what price.
 *
 * <h2>Why this is a separate record from {@link CompanyVendorResponse}</h2>
 * The list is a page of up to a hundred rows; the spend figures and the supplied
 * products each cost queries per vendor. Folding them into the list response as
 * nullable fields would either make the list expensive or make half its fields
 * mean "we didn't bother" - and a field that is sometimes absent for a reason the
 * caller cannot see is how a frontend ends up rendering a real zero as a blank.
 *
 * <h2>Purchase history is deliberately NOT here</h2>
 * It is paginated and it is its own screen, on the stakeholder's explicit
 * instruction. See {@code GET /api/company-vendors/{id}/purchases}.
 */
public record CompanyVendorDetailResponse(
        CompanyVendorResponse vendor,
        /* The live clients row for a VERIFIED entry; null for EXTERNAL. See PlatformVendorSummary. */
        PlatformVendorSummary platformVendor,
        VendorSpendSummary spend,
        /* Products this company has filed under this supplier, with the last price paid. */
        List<VendorProductPriceResponse> products) {
}
