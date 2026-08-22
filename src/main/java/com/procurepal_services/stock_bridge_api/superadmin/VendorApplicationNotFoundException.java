package com.procurepal_services.stock_bridge_api.superadmin;

/**
 * No waitlist application with that id. Maps to 404 via
 * {@link SuperAdminExceptionHandler}.
 *
 * <p>A sibling of {@link ClientNotFoundException} rather than a reuse of it,
 * because the two are told apart by the one caller who matters: a super admin
 * approving an application gets both an application id and, on success, a client
 * id, and "Client not found" in response to an application id would send them
 * looking at the wrong table.
 */
public class VendorApplicationNotFoundException extends RuntimeException {

    public VendorApplicationNotFoundException() {
        super("Vendor application not found");
    }
}
