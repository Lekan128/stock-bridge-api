package com.procurepal_services.stock_bridge_api.jwt;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates access tokens (JWTs). Refresh tokens are opaque
 * strings, not JWTs - see RefreshTokenService for those.
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The client is taken as a parameter rather than looked up from the user so the
     * platformOwner and clientType claims can never be minted from a stale or
     * re-fetched row - the caller has already loaded and validated the client it is
     * issuing the token for.
     *
     * Both of those claims are for the frontend to decide what to render, never for
     * the server to decide what to allow: PlatformOwnerGuard and VendorGuard each
     * re-read the clients row per request. See JwtClaims.
     */
    public String issueTenantAccessToken(User user, Client client, List<String> permissionCodes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .audience().add(TokenAudience.TENANT).and()
                .claim(JwtClaims.CLIENT_ID, user.getClientId().toString())
                .claim(JwtClaims.USERNAME, user.getUsername())
                .claim(JwtClaims.ROLE, user.getRole().getName())
                .claim(JwtClaims.PERMISSIONS, permissionCodes)
                .claim(JwtClaims.PLATFORM_OWNER, client.isPlatformOwner())
                .claim(JwtClaims.CLIENT_TYPE, ClientType.orDefault(client.getClientType()).name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpirationMs())))
                .signWith(signingKey)
                .compact();
    }

    public String issueSuperAdminAccessToken(SuperAdmin superAdmin) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(superAdmin.getId().toString())
                .audience().add(TokenAudience.SUPER_ADMIN).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpirationMs())))
                .signWith(signingKey)
                .compact();
    }

    /** Throws io.jsonwebtoken.JwtException (or a subtype) if the token is invalid, expired, or tampered with. */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long accessTokenExpirationSeconds() {
        return properties.accessTokenExpirationMs() / 1000;
    }
}
