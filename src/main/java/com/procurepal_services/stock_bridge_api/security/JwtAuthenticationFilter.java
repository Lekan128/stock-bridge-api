package com.procurepal_services.stock_bridge_api.security;

import com.procurepal_services.stock_bridge_api.jwt.JwtClaims;
import com.procurepal_services.stock_bridge_api.jwt.JwtService;
import com.procurepal_services.stock_bridge_api.jwt.TokenAudience;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads `Authorization: Bearer <token>`, validates it, and - if valid -
 * builds an Authentication with the right principal type and authorities.
 * Never rejects a request itself for a missing/invalid token: it just leaves
 * the SecurityContext unauthenticated and lets authorizeHttpRequests decide
 * (401/403 downstream), which is what lets permit-all endpoints keep working
 * even when a stale/garbage Authorization header is present.
 *
 * Grants an AUD_TENANT or AUD_SUPERADMIN marker authority based on the
 * token's `aud` claim, in addition to a tenant token's permission-code
 * authorities (for @PreAuthorize) or a super admin's ROLE_SUPER_ADMIN. See
 * SecurityConfig for how the AUD_* markers gate /api/superadmin/** vs
 * /api/**, which is what stops a tenant token from being used on an admin
 * route or vice versa.
 *
 * Registered before TenantResolutionFilter (see SecurityConfig) so that
 * filter can read the principal this one sets.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUDIENCE_TENANT_AUTHORITY = "AUD_TENANT";
    public static final String AUDIENCE_SUPERADMIN_AUTHORITY = "AUD_SUPERADMIN";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                Claims claims = jwtService.parseAndValidate(header.substring(BEARER_PREFIX.length()));
                Authentication authentication = toAuthentication(claims);
                if (authentication != null) {
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private Authentication toAuthentication(Claims claims) {
        Set<String> audience = claims.getAudience();
        if (audience == null) {
            return null;
        }
        if (audience.contains(TokenAudience.SUPER_ADMIN)) {
            List<GrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                    new SimpleGrantedAuthority(AUDIENCE_SUPERADMIN_AUTHORITY));
            SuperAdminPrincipal principal = SuperAdminPrincipal.fromClaims(claims, authorities);
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }
        if (audience.contains(TokenAudience.TENANT)) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority(AUDIENCE_TENANT_AUTHORITY));
            List<?> permissionCodes = claims.get(JwtClaims.PERMISSIONS, List.class);
            if (permissionCodes != null) {
                for (Object code : permissionCodes) {
                    authorities.add(new SimpleGrantedAuthority(String.valueOf(code)));
                }
            }
            AuthenticatedUserPrincipal principal = AuthenticatedUserPrincipal.fromClaims(claims, authorities);
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }
        return null;
    }
}
