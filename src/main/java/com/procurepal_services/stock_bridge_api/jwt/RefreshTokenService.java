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
 * Issues, rotates, and revokes refresh tokens. Shared by both the tenant
 * and super-admin auth flows - refresh_tokens is deliberately polymorphic
 * (subject_type/subject_id) for exactly this reason, so this logic isn't
 * duplicated between them.
 *
 * The raw token handed to the client is a 64-byte random value, never
 * persisted; only its SHA-256 hash is stored, so a DB read doesn't yield a
 * usable token.
 *
 * <h2>Rotation, and the lost reply</h2>
 * Every refresh retires the presented token and issues a new one, so a leaked token stops working
 * as soon as its owner refreshes. The weak point was the reply: if the response carrying the new
 * token never arrives (a dropped signal, the app closed mid-request, two tabs refreshing at once)
 * the client still holds the retired token, and refusing it logged the user out over nothing but
 * a bad connection - routine for a phone at a warehouse gate.
 *
 * So a retired token is accepted again in exactly one case: it was retired by ROTATION and the
 * token that replaced it has never been used. An unused replacement is the evidence that the
 * client never received it. The replacement is then retired in turn, so at any moment one token
 * per session is live. Once the replacement has been used, or the session logged out, the old
 * token is refused exactly as before.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Rotation chains are a hop or two; this only guards {@link #revokeSession} against a cycle. */
    private static final int MAX_CHAIN_HOPS = 50;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    /**
     * A token a refresh may proceed with. {@code live} is the session's current token, which the
     * rotation retires: the presented token itself, or - when the client is holding a token whose
     * replacement it never received - that unused replacement.
     */
    public record UsableToken(RefreshToken presented, RefreshToken live) {

        public SubjectType subjectType() {
            return presented.getSubjectType();
        }

        public UUID subjectId() {
            return presented.getSubjectId();
        }
    }

    @Transactional
    public String issue(SubjectType subjectType, UUID subjectId) {
        String rawToken = generateOpaqueToken();
        save(subjectType, subjectId, rawToken);
        return rawToken;
    }

    /**
     * The token a refresh may use, row-locked for the caller's transaction - see the class doc
     * for the one case a retired token qualifies. Empty means refuse.
     */
    @Transactional
    public Optional<UsableToken> findUsable(String rawToken) {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
                .filter(RefreshTokenService::notExpired);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken presented = found.get();
        if (presented.getRevokedAt() == null) {
            return Optional.of(new UsableToken(presented, presented));
        }
        if (presented.getReplacedById() == null) {
            // Retired by logout, or before replacement links existed: final.
            return Optional.empty();
        }
        return refreshTokenRepository.findByIdForUpdate(presented.getReplacedById())
                .filter(replacement -> replacement.getRevokedAt() == null)
                .filter(RefreshTokenService::notExpired)
                .map(replacement -> new UsableToken(presented, replacement));
    }

    /**
     * Retires the session's live token and issues its replacement. A stale presented token is
     * re-pointed at the new one too, so a client that loses this reply as well can still recover
     * with the token it holds - and only until the new token is used.
     */
    @Transactional
    public String rotate(UsableToken usable) {
        String rawToken = generateOpaqueToken();
        RefreshToken next = save(usable.subjectType(), usable.subjectId(), rawToken);

        RefreshToken live = usable.live();
        live.setRevokedAt(OffsetDateTime.now());
        live.setReplacedById(next.getId());
        refreshTokenRepository.save(live);

        RefreshToken presented = usable.presented();
        if (!presented.getId().equals(live.getId())) {
            presented.setReplacedById(next.getId());
            refreshTokenRepository.save(presented);
        }
        return rawToken;
    }

    /**
     * Logout. Ends the whole session, not only the presented token: a client logging out with a
     * token whose replacement it never received must not leave that replacement live, and the
     * presented token must not be re-admitted afterwards through its replacement link.
     */
    @Transactional
    public void revokeSession(String rawToken) {
        refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken)).ifPresent(presented -> {
            UUID next = presented.getReplacedById();
            retire(presented);
            presented.setReplacedById(null);
            refreshTokenRepository.save(presented);

            for (int hop = 0; next != null && hop < MAX_CHAIN_HOPS; hop++) {
                Optional<RefreshToken> successor = refreshTokenRepository.findByIdForUpdate(next);
                if (successor.isEmpty()) {
                    break;
                }
                next = successor.get().getReplacedById();
                retire(successor.get());
                refreshTokenRepository.save(successor.get());
            }
        });
    }

    private RefreshToken save(SubjectType subjectType, UUID subjectId, String rawToken) {
        RefreshToken token = RefreshToken.builder()
                .subjectType(subjectType)
                .subjectId(subjectId)
                .tokenHash(hash(rawToken))
                .expiresAt(OffsetDateTime.now().plus(Duration.ofMillis(jwtProperties.refreshTokenExpirationMs())))
                .build();
        return refreshTokenRepository.save(token);
    }

    private static void retire(RefreshToken token) {
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(OffsetDateTime.now());
        }
    }

    private static boolean notExpired(RefreshToken token) {
        return token.getExpiresAt().isAfter(OffsetDateTime.now());
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
