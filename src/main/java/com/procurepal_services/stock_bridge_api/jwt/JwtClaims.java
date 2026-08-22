package com.procurepal_services.stock_bridge_api.jwt;

/** Custom (non-registered) claim names used in access tokens. */
public final class JwtClaims {

    public static final String CLIENT_ID = "clientId";
    public static final String USERNAME = "username";
    public static final String ROLE = "role";
    public static final String PERMISSIONS = "permissions";

    /**
     * Whether the token holder's client is ProcurePal. Present so the frontend can
     * decide what to render (marketplace-admin nav, the RequirePlatformOwner route
     * guard) without an extra round trip.
     *
     * NOT an authorization input on the server: PlatformOwnerGuard re-reads
     * clients.is_platform_owner per request, so revoking platform ownership takes
     * effect immediately instead of whenever an outstanding 15-minute token expires.
     */
    public static final String PLATFORM_OWNER = "platformOwner";

    /**
     * The token holder's client type - the string name of a
     * {@link com.procurepal_services.stock_bridge_api.entity.ClientType}, so
     * {@code "COMPANY"} or {@code "VENDOR"}. Present for exactly the reason
     * {@link #PLATFORM_OWNER} is, and it sits beside it deliberately: the frontend
     * needs to decide what to RENDER - which nav groups exist, which route guards
     * pass - without an extra round trip, and permission codes alone cannot tell it,
     * because permissions hang off global roles.
     *
     * <p>A string rather than an {@code isVendor} boolean, matching the column: a
     * second boolean beside platformOwner would make contradictory states
     * expressible, and a third kind of account later would need a third claim. The
     * two are ORTHOGONAL - ProcurePal is {@code "COMPANY"} with
     * {@code platformOwner: true}, because it buys and has staff as well as selling.
     * Frontend code that treats "not the platform owner" as "an ordinary buyer" was
     * right before this claim existed and is wrong now.
     *
     * <p>NOT an authorization input on the server, and this is the important half:
     * VendorGuard re-reads clients.client_type per request, exactly as
     * PlatformOwnerGuard re-reads is_platform_owner, so changing an account's kind
     * takes effect on the very next request instead of whenever an outstanding
     * 15-minute access token happens to expire. A server-side check that trusted
     * this claim would be honouring a decision the platform had already reversed.
     */
    public static final String CLIENT_TYPE = "clientType";

    private JwtClaims() {
    }
}
