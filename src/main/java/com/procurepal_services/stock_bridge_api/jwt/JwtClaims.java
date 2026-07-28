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

    private JwtClaims() {
    }
}
