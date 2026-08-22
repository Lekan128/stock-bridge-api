package com.procurepal_services.stock_bridge_api.entity;

/**
 * Which of the two kinds of supplier a {@link CompanyVendor} row records, stored
 * as its name in {@code company_vendors.vendor_kind}.
 *
 * <h2>Why the kinds share one table</h2>
 * They share every user-facing behaviour: one list, sorted and searched together,
 * linked from products the same way, and both answering "what did we last pay
 * this supplier". Two tables would mean a UNION in every read and two nullable
 * foreign keys on {@code products}. What differs is where the row's data comes
 * from and who may edit it - and that is exactly what the CHECK constraints in
 * V11__vendors.sql pin down, rather than leaving to service code that could
 * silently write an incoherent row.
 */
public enum CompanyVendorKind {

    /**
     * A platform vendor (or ProcurePal) this company has actually bought from.
     * Created automatically at purchase, never by hand, and
     * {@link CompanyVendor#getPlatformClientId()} is required - the row asserts a
     * fact about the platform, so it must name the account it is asserting it
     * about.
     *
     * <p>The owning company may not edit it. It did not write the row and cannot
     * change what it says: "you traded with them" is not an opinion.
     */
    VERIFIED,

    /**
     * A supplier the company deals with entirely off-platform - the local miller,
     * the diesel supplier - typed in and owned by the company. Has no
     * {@code platformClientId}, and must not have one: an external entry carrying
     * a real vendor's client id would claim a platform relationship that the buyer
     * simply asserted. Name and contact phone are required, because a hand-typed
     * supplier with neither is not a record of anything.
     */
    EXTERNAL;

    /** True when this kind requires (and is required to have) a platform client id. */
    public boolean requiresPlatformClient() {
        return this == VERIFIED;
    }

    /** True when the owning company may edit the row's own fields. */
    public boolean isCompanyEditable() {
        return this == EXTERNAL;
    }
}
