package com.procurepal_services.stock_bridge_api.jwt;

import com.procurepal_services.stock_bridge_api.entity.RefreshToken;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import com.procurepal_services.stock_bridge_api.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, validates, and revokes refresh tokens. Shared by both the tenant
 * and super-admin auth flows - refresh_tokens is deliberately polymorphic
 * (subject_type/subject_id) for exactly this reason, so this logic isn't
 * duplicated between them.
 *
 * The raw token handed to the client is a 64-byte random value, never
 * persisted; only its SHA-256 hash is stored, so a DB read doesn't yield a
 * usable token.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    @Transactional
    public String issue(SubjectType subjectType, UUID subjectId) {
        String rawToken = generateOpaqueToken();
        RefreshToken token = RefreshToken.builder()
                .subjectType(subjectType)
                .subjectId(subjectId)
                .tokenHash(hash(rawToken))
                .expiresAt(OffsetDateTime.now().plus(Duration.ofMillis(jwtProperties.refreshTokenExpirationMs())))
                .build();
        refreshTokenRepository.save(token);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findValid(String rawToken) {
        return refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> token.getRevokedAt() == null)
                .filter(token -> token.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    @Transactional
    public void revoke(RefreshToken token) {
        token.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(token);
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(this::revoke);
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
