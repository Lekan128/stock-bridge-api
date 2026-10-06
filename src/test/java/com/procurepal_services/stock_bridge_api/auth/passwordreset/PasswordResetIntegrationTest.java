package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.RefreshRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCheckRequest;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCheckResponse;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCompleteRequest;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetRequest;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.PasswordResetToken;
import com.procurepal_services.stock_bridge_api.repository.PasswordResetTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Self-service password reset end to end - real HTTP, real security chain, the local
 * docker-compose Postgres. Requires {@code docker compose up -d} at the project root.
 *
 * <p>The raw token only ever exists in the email, so tests that need a working link
 * insert a token row whose hash they know, exactly as the service would mint it. The
 * request endpoint is covered by what it writes. No properties are set so this shares
 * the suite's default cached context (and its connection pool).
 *
 * <p>The per-IP request limit (10 an hour) is shared by every test in that context, so
 * this class keeps its calls to {@code /request} few; everything else here goes
 * through check and complete, which are not rate-limited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PasswordResetIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "a-brand-new-password";
    private static final String BASE = "/api/auth/password-reset";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ========================================================================
    // Request: the same answer whoever asks.
    // ========================================================================

    @Test
    void anUnknownEmailIsAcceptedAndNothingIsWritten() {
        String nobody = "nobody-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<String> response = request(nobody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE email_address = ?", Long.class, nobody))
                .isZero();
    }

    /** Same status for a real account as for nobody - and only the newest link stays live. */
    @Test
    void aKnownEmailGetsOneLiveLinkAndAskingAgainRetiresTheOld() {
        TenantLoginResponse owner = signup("Reset Request Co");
        String email = owner.user().username();

        assertThat(request(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(request(email.toUpperCase()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID userId = owner.user().id();
        assertThat(countTokens(userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ? "
                        + "AND consumed_at IS NULL AND superseded_at IS NULL", Long.class, userId))
                .isEqualTo(1);
        // Stored as a hash and a lowercased address, never as the raw token.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ? AND email_address = ?",
                Long.class, userId, email.toLowerCase())).isEqualTo(2);
    }

    // ========================================================================
    // Check: says which account, never spends the link.
    // ========================================================================

    @Test
    void checkingNamesTheAccountAndDoesNotUseTheLinkUp() {
        TenantLoginResponse owner = signup("Reset Check Co");
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().plusHours(1));

        ResponseEntity<PasswordResetCheckResponse> first = check(raw);
        ResponseEntity<PasswordResetCheckResponse> second = check(raw);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().companyName()).isEqualTo(owner.user().clientName());
        assertThat(first.getBody().clientIdentifier()).isEqualTo(owner.user().clientIdentifier());
        assertThat(first.getBody().login()).isEqualTo(owner.user().username());
    }

    @Test
    void anExpiredLinkIsRefusedWithTheOneMessage() {
        TenantLoginResponse owner = signup("Reset Expired Co");
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().minusMinutes(1));

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                BASE + "/check", new PasswordResetCheckRequest(raw), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(InvalidPasswordResetTokenException.MESSAGE);
    }

    /** The link proved control of one inbox; if the account no longer uses it, the proof is void. */
    @Test
    void aLinkSentToAnAddressTheAccountNoLongerUsesIsRefused() {
        TenantLoginResponse owner = signup("Reset Moved Co");
        String raw = insertToken(owner, "old-" + UUID.randomUUID() + "@example.com", OffsetDateTime.now().plusHours(1));

        assertThat(check(raw).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ========================================================================
    // Complete: new password, signed in, every other session ended.
    // ========================================================================

    @Test
    void completingSetsThePasswordSignsInAndLogsOutOtherSessions() {
        TenantLoginResponse owner = signup("Reset Complete Co");
        String oldRefreshToken = owner.tokens().refreshToken();
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().plusHours(1));

        ResponseEntity<TenantLoginResponse> response = restTemplate.postForEntity(
                BASE + "/complete", new PasswordResetCompleteRequest(raw, NEW_PASSWORD), TenantLoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().tokens().accessToken()).isNotBlank();
        assertThat(response.getBody().user().id()).isEqualTo(owner.user().id());

        // The session that existed before the reset is over.
        assertThat(restTemplate.postForEntity("/api/auth/refresh", new RefreshRequest(oldRefreshToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The new password works and the old one does not.
        assertThat(login(owner, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(owner, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Using the link proved the inbox, so the address is now confirmed.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_email_verified FROM users WHERE id = ?", Boolean.class, owner.user().id())).isTrue();
    }

    @Test
    void aLinkWorksExactlyOnce() {
        TenantLoginResponse owner = signup("Reset Once Co");
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().plusHours(1));

        assertThat(complete(raw, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(complete(raw, "yet-another-password").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(check(raw).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A rejected password must not burn the link - the person fixes it and tries again. */
    @Test
    void aTooShortPasswordIsRefusedAndTheLinkStillWorks() {
        TenantLoginResponse owner = signup("Reset Short Co");
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().plusHours(1));

        assertThat(complete(raw, "short").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(check(raw).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(owner, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSuspendedCompanysLinkIsRefused() {
        TenantLoginResponse owner = signup("Reset Suspended Co");
        String raw = insertToken(owner, owner.user().username(), OffsetDateTime.now().plusHours(1));
        jdbcTemplate.update("UPDATE clients SET is_active = FALSE WHERE slug = ?", owner.user().clientIdentifier());

        assertThat(complete(raw, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------

    private ResponseEntity<String> request(String email) {
        return restTemplate.postForEntity(BASE + "/request", new PasswordResetRequest(email), String.class);
    }

    private ResponseEntity<PasswordResetCheckResponse> check(String raw) {
        return restTemplate.postForEntity(
                BASE + "/check", new PasswordResetCheckRequest(raw), PasswordResetCheckResponse.class);
    }

    private ResponseEntity<String> complete(String raw, String newPassword) {
        return restTemplate.postForEntity(
                BASE + "/complete", new PasswordResetCompleteRequest(raw, newPassword), String.class);
    }

    private ResponseEntity<String> login(TenantLoginResponse owner, String password) {
        return restTemplate.postForEntity("/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), owner.user().username(), password), String.class);
    }

    private TenantLoginResponse signup(String name) {
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + UUID.randomUUID().toString().substring(0, 8),
                null,
                "owner-" + UUID.randomUUID() + "@example.com",
                PASSWORD,
                PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    /** A token row exactly as PasswordResetService mints one, for a raw value this test knows. */
    private String insertToken(TenantLoginResponse owner, String address, OffsetDateTime expiresAt) {
        String raw = "test-" + UUID.randomUUID();
        tokenRepository.save(PasswordResetToken.builder()
                .userId(owner.user().id())
                .emailAddress(address.toLowerCase())
                .tokenHash(sha256Hex(raw))
                .expiresAt(expiresAt)
                .build());
        return raw;
    }

    private long countTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Long.class, userId);
    }

    private static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
