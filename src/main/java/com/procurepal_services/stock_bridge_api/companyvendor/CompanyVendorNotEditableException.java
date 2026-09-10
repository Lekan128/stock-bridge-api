package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;

/**
 * The caller tried to edit a VERIFIED entry. Maps to 409, not 403: the caller does
 * hold MANAGE_VENDORS and the request would succeed against a different row, so
 * this is a conflict with the state of THIS resource rather than a permission
 * failure. Answering 403 would send an owner off to check their role for a rule
 * that has nothing to do with roles.
 *
 * <p>The rule itself is {@link CompanyVendor#isEditableByOwningCompany()}: a
 * VERIFIED row was written by the platform on the strength of a real purchase, and
 * "you traded with them" is not an opinion the buyer gets to rewrite. Deactivating
 * one is still allowed - that is the buyer saying they have stopped using the
 * supplier, which IS their opinion to hold.
 */
public class CompanyVendorNotEditableException extends RuntimeException {

    public CompanyVendorNotEditableException(String vendorName) {
        super("\"" + vendorName + "\" was added automatically when you bought from them on ProcurePaddy, "
                + "so its details cannot be edited here. You can remove it from your directory instead.");
    }
}
