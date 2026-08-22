package com.procurepal_services.stock_bridge_api.entity;

/**
 * What kind of account a {@link Client} is, stored as its name in
 * {@code clients.client_type}.
 *
 * <h2>Why a type and not a second boolean</h2>
 * The restrictions this distinction carries are mutually exclusive by nature - a
 * seller cannot buy and cannot create staff, a buyer can do both - so the column
 * that decides them should be able to hold exactly one answer at a time. An
 * {@code is_vendor} boolean beside the existing {@code is_platform_owner} would
 * make states like "vendor that may place orders" expressible, and a third kind
 * later would mean a third boolean and eight combinations of which most mean
 * nothing.
 *
 * <h2>Orthogonal to {@code isPlatformOwner}</h2>
 * {@link Client#isPlatformOwner()} does not describe what an account may do - it
 * names the single account that OWNS the marketplace. ProcurePal is
 * {@link #COMPANY} (it buys, it has staff) AND platform owner. Folding the two
 * together would either strip ProcurePal of the ordinary-tenant behaviour it
 * relies on, or hand every vendor the platform-owner surfaces.
 *
 * <h2>This is not the "vendor" in {@link CompanyVendor}</h2>
 * A {@link #VENDOR} client is a SELLER on the marketplace. A {@code CompanyVendor}
 * row is an entry in one buying company's private list of suppliers, which may
 * refer to a platform vendor or to somebody with no account at all. Read the
 * V11__vendors.sql header before writing anything that touches both.
 */
public enum ClientType {

    /**
     * A buying company: the ordinary tenant this application started with. Browses
     * and buys on the marketplace, runs its own inventory, creates its own users,
     * and keeps a vendor directory. Every row that existed before V11 is one of
     * these, which is why it is the column default.
     */
    COMPANY,

    /**
     * A marketplace seller. Has exactly ONE user account and cannot create more,
     * cannot place orders, and sells its own products. Created only by a super
     * admin, from a {@link VendorWaitlistApplication} or directly.
     *
     * <p>None of those restrictions are enforced by this constant on its own -
     * see {@code VendorGuard} for the authorization side, and the VENDOR role's
     * seeded permission set (which deliberately excludes MANAGE_USERS and
     * PLACE_ORDERS) for the capability side.
     */
    VENDOR;

    /** True for a seller account. Reads better than an {@code == VENDOR} at call sites. */
    public boolean isVendor() {
        return this == VENDOR;
    }

    /**
     * The type, or {@link #COMPANY} when it is absent.
     *
     * <p>Exists because the value ends up in a JWT claim and a login response, and
     * a null there would be rendered by the frontend as "no kind of account", which
     * is not one of the answers. The column is NOT NULL and defaults to COMPANY, so
     * a row loaded from the database always has one; this covers an instance
     * assembled in code that never went through the builder's default. COMPANY is
     * the right fallback for the same reason it is the column default - it is what
     * every account was before vendors existed, and it grants nothing.
     */
    public static ClientType orDefault(ClientType value) {
        return value == null ? COMPANY : value;
    }
}
