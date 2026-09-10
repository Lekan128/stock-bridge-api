package com.procurepal_services.stock_bridge_api.vendor;

/**
 * The caller's company is not the kind of account this surface is for. Maps to
 * 403 via {@link VendorAccessExceptionHandler}.
 *
 * <p>Like {@code PlatformOwnerNotAllowedException}, this is a distinct failure
 * from "you lack the permission". Permissions hang off global roles and are
 * therefore held by whole classes of user; what this reports is that the caller's
 * CLIENT is the wrong kind - a buying company on a seller surface, or a vendor on
 * a surface only ProcurePal runs. Both checks are required and neither is
 * sufficient.
 *
 * <h2>Two messages, one exception type</h2>
 * The two failures a caller can hit here - "you are not a vendor" and "your
 * account cannot sell" - are the same refusal from the caller's point of view and
 * carry the same status, so they share a type and differ only in wording. They
 * are static factories rather than a public constructor so the message text stays
 * in one file and cannot drift between call sites.
 *
 * <p>Neither message names who the account belongs to, what kind it is, or which
 * accounts would be allowed. A caller probing a vendor surface learns only that
 * they may not use it.
 */
public class VendorNotAllowedException extends RuntimeException {

    private VendorNotAllowedException(String message) {
        super(message);
    }

    /** The current tenant is not a vendor account. */
    public static VendorNotAllowedException notAVendor() {
        return new VendorNotAllowedException("This action is restricted to vendor accounts.");
    }

    /**
     * The current tenant may not sell on the marketplace - it is neither a vendor
     * nor the platform owner.
     */
    public static VendorNotAllowedException notASeller() {
        return new VendorNotAllowedException("This action is restricted to accounts that sell on the marketplace.");
    }
}
