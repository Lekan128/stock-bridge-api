package com.procurepal_services.stock_bridge_api.jwt;

/**
 * Values used in the JWT `aud` claim to mark which side of the app a token is
 * for. SecurityConfig uses this to reject a tenant token on a superadmin
 * route and vice versa.
 */
public final class TokenAudience {

    public static final String TENANT = "tenant";
    public static final String SUPER_ADMIN = "superadmin";

    private TokenAudience() {
    }
}
