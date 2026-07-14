package com.procurepal_services.stock_bridge_api.security;

import com.procurepal_services.stock_bridge_api.jwt.JwtClaims;
import io.jsonwebtoken.Claims;
import java.util.Collection;
import java.util.UUID;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal for an authenticated tenant user, reconstructed
 * from a validated JWT's claims by JwtAuthenticationFilter - this is a
 * stateless scheme, so there's no DB lookup per request. isEnabled() always
 * returns true here: the user's active status was checked at login time (and
 * would be re-checked on refresh), not on every request; the short
 * access-token expiry (app.jwt.access-token-expiration-ms) bounds how stale
 * that can get.
 */
@Getter
public class AuthenticatedUserPrincipal implements UserDetails, TenantPrincipal {

    private final UUID userId;
    private final UUID clientId;
    private final String username;
    private final Collection<? extends GrantedAuthority> authorities;

    public AuthenticatedUserPrincipal(
            UUID userId, UUID clientId, String username, Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.clientId = clientId;
        this.username = username;
        this.authorities = authorities;
    }

    public static AuthenticatedUserPrincipal fromClaims(
            Claims claims, Collection<? extends GrantedAuthority> authorities) {
        return new AuthenticatedUserPrincipal(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(JwtClaims.CLIENT_ID, String.class)),
                claims.get(JwtClaims.USERNAME, String.class),
                authorities);
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
