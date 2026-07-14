package com.procurepal_services.stock_bridge_api.jwt;

/** Custom (non-registered) claim names used in access tokens. */
public final class JwtClaims {

    public static final String CLIENT_ID = "clientId";
    public static final String USERNAME = "username";
    public static final String ROLE = "role";
    public static final String PERMISSIONS = "permissions";

    private JwtClaims() {
    }
}
