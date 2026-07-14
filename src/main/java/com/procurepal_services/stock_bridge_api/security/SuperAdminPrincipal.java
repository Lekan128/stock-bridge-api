package com.procurepal_services.stock_bridge_api.security;

import io.jsonwebtoken.Claims;
import java.util.Collection;
import java.util.UUID;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal for an authenticated super admin. Deliberately
 * does NOT implement TenantPrincipal - a super admin belongs to no tenant, so
 * TenantResolutionFilter correctly leaves TenantContext empty for it.
 */
@Getter
public class SuperAdminPrincipal implements UserDetails {

    private final UUID superAdminId;
    private final Collection<? extends GrantedAuthority> authorities;

    public SuperAdminPrincipal(UUID superAdminId, Collection<? extends GrantedAuthority> authorities) {
        this.superAdminId = superAdminId;
        this.authorities = authorities;
    }

    public static SuperAdminPrincipal fromClaims(Claims claims, Collection<? extends GrantedAuthority> authorities) {
        return new SuperAdminPrincipal(UUID.fromString(claims.getSubject()), authorities);
    }

    @Override
    public String getUsername() {
        return superAdminId.toString();
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
