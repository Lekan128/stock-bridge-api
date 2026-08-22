package com.procurepal_services.stock_bridge_api.superadmin;

/**
 * No vendor with that id. Maps to 404 via {@link SuperAdminExceptionHandler}.
 *
 * <h2>Why an id that IS a client but is NOT a vendor lands here</h2>
 * The lookup behind this is {@code findByIdAndClientType(id, VENDOR)}, so a
 * perfectly real buying company's id reads as "not found" on the vendor surface.
 * That conflation is deliberate and matches {@code UserNotFoundException}'s: the
 * pair (id, kind) is what the caller asked about, and answering "that exists but
 * is the wrong kind" would let a caller turn any client id into a probe. It also
 * keeps the vendor endpoints from ever serving a COMPANY row under a vendor
 * heading, which is the failure that would matter - a screen showing a buyer's
 * details as a seller's.
 */
public class VendorNotFoundException extends RuntimeException {

    public VendorNotFoundException() {
        super("Vendor not found");
    }
}
