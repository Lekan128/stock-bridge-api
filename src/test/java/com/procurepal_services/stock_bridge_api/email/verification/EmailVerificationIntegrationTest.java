package com.procurepal_services.stock_bridge_api.email.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.verification.dto.ResendVerificationResponse;
import com.procurepal_services.stock_bridge_api.email.verification.dto.VerifyEmailRequest;
import com.procurepal_services.stock_bridge_api.email.verification.dto.VerifyEmailResponse;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.profile.dto.UpdateProfileRequest;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres over
 * Testcontainers. Requires `docker compose up -d` at the project root.
 *
 * <h2>Why the raw token comes from the service and not from an inbox</h2>
 * The whole design is that the raw value exists only in the recipient's mailbox,
 * and nothing is actually mailed in a test profile (no SES identity is configured,
 * so EmailSender logs and drops). Rather than mock the dispatcher and dig the URL
 * out of a rendered template - which would test the template, not the flow - these
 * tests call {@link EmailVerificationService#issueLink} directly for the one thing
 * only the issuer knows, then drive everything afterwards over real HTTP. The
 * assertion that the token in that URL is genuinely absent from the database is
 * itself one of the tests below.
 *
 * <p>JdbcTemplate is used for the two things JPA cannot express: reading a column
 * the entity maps as {@code updatable = false}, and aging a token past its expiry
 * without waiting a day. Same justification MarketplaceAnalyticsIntegrationTest
 * gives for planting rows directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class EmailVerificationIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    // ========================================================================
    // STORAGE
    // ========================================================================

    /**
     * The acceptance criterion the whole storage design exists for: a dump of
     * {@code email_verification_tokens} must not hand anybody a working link.
     *
     * <p>Asserted three ways because each catches a different mistake - that the
     * raw value is not the stored key (a service that forgot to hash), that no row
     * anywhere holds it verbatim (a stray column or a debug field), and that what
     * IS stored is exactly its SHA-256 (a hash that silently changed algorithm and
     * would invalidate every outstanding link).
     */
    @Test
    void theRawTokenIsNeverStoredOnlyItsHash() {
        TenantLoginResponse owner = signup("Token Storage Co");
        String rawToken = issueRawToken(owner);

        assertThat(rawToken).isNotBlank();
        assertThat(countTokensWithHash(rawToken)).isZero();
        assertThat(countTokensWithHash(sha256Hex(rawToken))).isEqualTo(1);
        // Belt and braces: not present verbatim in any row's hash column at all.
        Long verbatim = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_tokens WHERE token_hash LIKE ?",
                Long.class, "%" + rawToken + "%");
        assertThat(verbatim).isZero();
    }

    /**
     * A brand-new signup must arrive holding a link. Without this, the account is
     * unverified with no way to become verified, and every order receipt it will
     * ever be sent is silently dropped by EmailEligibility.
     *
     * <p>Exactly one, not one-or-more: the welcome email carries the link rather
     * than a second "confirm your address" mail following it, so a count of two here
     * would mean a new user is getting two competing messages.
     */
    @Test
    void signupIssuesExactlyOneLiveVerificationToken() {
        TenantLoginResponse owner = signup("Signup Issues Token Co");

        assertThat(countLiveTokensFor(owner)).isEqualTo(1);
    }

    /**
     * At most one working link per account, at every instant. Every issued token
     * retires the ones before it - see EmailVerificationService.supersedeOutstanding
     * for the three reasons.
     */
    @Test
    void issuingANewLinkSupersedesTheOutstandingOne() {
        TenantLoginResponse owner = signup("Supersede Co");
        String first = issueRawToken(owner);
        String second = issueRawToken(owner);

        assertThat(countLiveTokensFor(owner)).isEqualTo(1);
        assertThat(verify(second).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The superseded one is dead, and says so with the same message everything
        // else does.
        assertThat(verifyExpectingRefusal(first)).isEqualTo(refusalMessage());
    }

    // ========================================================================
    // REDEEM
    // ========================================================================

    /**
     * Happy path, end to end, and note what is NOT on this request: no Authorization
     * header, no tenant, no session. The endpoint has to work for somebody who has
     * never signed in - which is most of the point, since an invited sub-user may
     * not even know their password yet.
     */
    @Test
    void verifyingWithNoAuthenticationAndNoTenantContextConfirmsTheAddress() {
        TenantLoginResponse owner = signup("Happy Verify Co");
        assertThat(profile(owner).emailVerified()).isFalse();

        String rawToken = issueRawToken(owner);
        ResponseEntity<VerifyEmailResponse> response = verify(rawToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().verified()).isTrue();
        assertThat(response.getBody().message()).isNotBlank();

        ProfileResponse me = profile(owner);
        assertThat(me.emailVerified()).isTrue();

        // The row records WHEN, not just whether - that timestamp is the only way to
        // tell a human-verified row from one V8 grandfathered.
        assertThat(userRepository.findById(owner.user().id()).orElseThrow().getEmailVerifiedAt()).isNotNull();
    }

    /**
     * Replay. A link that already worked must not work again: single-use is what
     * stops a leaked or forwarded mail from being redeemable indefinitely, and the
     * second click has nothing left to do anyway.
     */
    @Test
    void aConsumedTokenCannotBeReplayed() {
        TenantLoginResponse owner = signup("Replay Co");
        String rawToken = issueRawToken(owner);

        assertThat(verify(rawToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(verifyExpectingRefusal(rawToken)).isEqualTo(refusalMessage());
    }

    @Test
    void anExpiredTokenIsRefused() {
        TenantLoginResponse owner = signup("Expiry Co");
        String rawToken = issueRawToken(owner);
        expire(rawToken);

        assertThat(verifyExpectingRefusal(rawToken)).isEqualTo(refusalMessage());
        assertThat(profile(owner).emailVerified()).isFalse();
    }

    /**
     * The security property, asserted as one test because it is one property: the
     * four ways a token can be bad must be indistinguishable from outside.
     *
     * <p>If "expired" could be told apart from "never existed", an attacker
     * enumerating tokens would learn when a guess had hit a real row, which turns a
     * blind search into one with feedback. If "already used" could be told apart,
     * anybody holding a forwarded link would learn the account exists and has been
     * acted on. Status code and body are both checked, because the status is as much
     * of an oracle as the message.
     */
    @Test
    void everyWayATokenCanFailProducesTheSameStatusAndTheSameMessage() {
        TenantLoginResponse owner = signup("No Oracle Co");

        String consumed = issueRawToken(owner);
        verify(consumed);

        TenantLoginResponse other = signup("No Oracle Expired Co");
        String expired = issueRawToken(other);
        expire(expired);

        TenantLoginResponse third = signup("No Oracle Superseded Co");
        String superseded = issueRawToken(third);
        issueRawToken(third);

        String unknown = "definitely-not-a-real-token-" + UUID.randomUUID();

        for (String token : new String[] {consumed, expired, superseded, unknown}) {
            ResponseEntity<ApiError> response =
                    restTemplate.postForEntity("/api/email/verify", new VerifyEmailRequest(token), ApiError.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo(refusalMessage());
        }
    }

    /**
     * A token is a claim about an inbox, not about a user id. If it were the latter,
     * requesting a link to an address you control and then editing your profile to
     * one you do not would mark somebody else's address verified - and verified is
     * what makes an address eligible for a company's order mail.
     */
    @Test
    void aTokenCannotVerifyAnAddressTheAccountNoLongerHas() {
        TenantLoginResponse owner = signup("Address Binding Co");
        String rawToken = issueRawToken(owner);

        changeEmail(owner, "somebody-else-" + UUID.randomUUID() + "@example.com");

        assertThat(verifyExpectingRefusal(rawToken)).isEqualTo(refusalMessage());
        assertThat(profile(owner).emailVerified()).isFalse();
    }

    /**
     * The other half of the same hole, closed from the other side: an
     * already-redeemed verification must not survive a move to a different address,
     * or PUT /api/me becomes a way to mark any address verified at all.
     */
    @Test
    void changingTheProfileAddressClearsAnExistingVerification() {
        TenantLoginResponse owner = signup("Reverify Co");
        assertThat(verify(issueRawToken(owner)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile(owner).emailVerified()).isTrue();

        changeEmail(owner, "moved-" + UUID.randomUUID() + "@example.com");

        ProfileResponse me = profile(owner);
        assertThat(me.emailVerified()).isFalse();
        assertThat(userRepository.findById(owner.user().id()).orElseThrow().getEmailVerifiedAt()).isNull();
    }

    @Test
    void aMissingTokenIsRejectedByValidationRatherThanReachingTheService() {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/email/verify", new VerifyEmailRequest("   "), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isNotBlank();
    }

    // ========================================================================
    // RESEND
    // ========================================================================

    /**
     * The abuse bound. This endpoint makes the server send mail to an address the
     * caller controls, and bounces from it are scored against a sending domain every
     * tenant shares - so unbounded, one account could get order receipts suspended
     * platform-wide.
     */
    @Test
    void resendIsRateLimitedAndSaysHowLongToWait() {
        TenantLoginResponse owner = signup("Resend Limit Co");

        for (int attempt = 1; attempt <= 3; attempt++) {
            ResponseEntity<ResendVerificationResponse> allowed = resend(owner, ResendVerificationResponse.class);
            assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(allowed.getBody()).isNotNull();
            assertThat(allowed.getBody().sent()).isTrue();
        }

        HttpResponse<String> refused = resendWithoutClientRetries(owner);
        assertThat(refused.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(refused.body()).contains("Too many");
        // Machine-readable half of the refusal - what the frontend disables the
        // button with. Never zero, which would read as "retry immediately".
        String retryAfter = refused.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse(null);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    /** Each resend supersedes the last, so pressing it repeatedly never accumulates live links. */
    @Test
    void resendLeavesAtMostOneLiveTokenBehind() {
        TenantLoginResponse owner = signup("Resend Supersede Co");

        resend(owner, ResendVerificationResponse.class);
        resend(owner, ResendVerificationResponse.class);

        assertThat(countLiveTokensFor(owner)).isEqualTo(1);
    }

    /**
     * Not an error. The caller asked for their address to be confirmed and it
     * already is, which is the state they wanted - a 4xx would make the frontend
     * render a failure for a success.
     */
    @Test
    void resendingAfterVerificationSendsNothingAndSaysSo() {
        TenantLoginResponse owner = signup("Resend Verified Co");
        verify(issueRawToken(owner));

        ResponseEntity<ResendVerificationResponse> response = resend(owner, ResendVerificationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sent()).isFalse();
        assertThat(response.getBody().message()).contains("already");
    }

    /**
     * Authenticated by structure, not by permission: no principal, no endpoint.
     * This is the assertion that the resend primitive - which makes the server send
     * mail to a caller-controlled address - is not reachable anonymously, unlike its
     * sibling {@code POST /api/email/verify}.
     *
     * <p>403 rather than 401 is this application's chain-wide answer to a request
     * with no token at all: no {@code AuthenticationEntryPoint} is configured, so an
     * anonymous request denied by an {@code authorizeHttpRequests} rule comes back
     * as an access denial rather than an authentication challenge. That is
     * pre-existing behaviour shared by every {@code /api/**} path, not something
     * this endpoint chooses, and the 401s elsewhere in the suite are all
     * bad-credential responses from the login endpoints rather than this case.
     */
    @Test
    void resendRequiresAuthentication() {
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/me/email-verification", HttpMethod.POST, HttpEntity.EMPTY, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ========================================================================
    // PROFILE SURFACE
    // ========================================================================

    /**
     * What module E renders the "confirm your email" prompt from. Both fields are
     * read-only here - the promotional one is written through module C's
     * PUT /api/me/email-preferences, and the verified one only ever by redeeming a
     * token.
     */
    @Test
    void profileExposesVerificationStateAndPromotionalConsent() {
        TenantLoginResponse owner = signup("Profile Fields Co");

        ProfileResponse me = profile(owner);

        assertThat(me.emailVerified()).isFalse();
        // Opt-out model: consent is presumed and withdrawal is one click (RFC 8058).
        assertThat(me.receivePromotionalEmail()).isTrue();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique, null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        ResponseEntity<TenantLoginResponse> response =
                restTemplate.postForEntity("/api/clients/signup", request, TenantLoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * The one thing only the issuer knows. Everything after this point goes over
     * real HTTP; this stands in for the mail client the raw value would otherwise
     * only ever reach.
     */
    private String issueRawToken(TenantLoginResponse owner) {
        User user = userRepository.findById(owner.user().id()).orElseThrow();
        VerificationLink link = emailVerificationService.issueLink(user);
        assertThat(link).isNotNull();
        assertThat(link.url()).contains("/verify-email?token=");
        String encoded = link.url().substring(link.url().indexOf("token=") + "token=".length());
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private ResponseEntity<VerifyEmailResponse> verify(String rawToken) {
        // Deliberately no auth headers: this endpoint is permit-all and must work
        // for somebody who has never signed in.
        return restTemplate.postForEntity(
                "/api/email/verify", new VerifyEmailRequest(rawToken), VerifyEmailResponse.class);
    }

    private String verifyExpectingRefusal(String rawToken) {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/email/verify", new VerifyEmailRequest(rawToken), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().message();
    }

    /**
     * The single message every refusal carries, obtained from the exception rather
     * than duplicated as a literal - so a copy edit cannot make these tests pass
     * while the endpoint starts distinguishing failure modes.
     */
    private static String refusalMessage() {
        return new InvalidVerificationTokenException("probe").getMessage();
    }

    /**
     * The 429 assertion, made with the JDK's own HTTP client rather than
     * {@code TestRestTemplate}, and the reason is a genuine trap rather than a
     * preference.
     *
     * <p>TestRestTemplate is backed by Apache HttpClient 5, whose default retry
     * strategy treats 429 as retryable and <em>sleeps for the duration in the
     * {@code Retry-After} header</em> before trying again. The header this endpoint
     * sends is honest - roughly the configured window, so about an hour - which
     * means the assertion below would pass only after the test had slept for an
     * hour. It does not fail; it hangs, which is much worse to diagnose. The JDK
     * client performs no automatic retries and reports the response as given.
     *
     * <p>Worth knowing beyond this file: any Java caller of this API using Apache
     * HttpClient with default settings will do the same thing. Browsers and
     * {@code fetch} do not, so the frontend is unaffected.
     */
    private HttpResponse<String> resendWithoutClientRetries(TenantLoginResponse owner) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/me/email-verification"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.tokens().accessToken())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not call the resend endpoint", e);
        }
    }

    private <T> ResponseEntity<T> resend(TenantLoginResponse owner, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/me/email-verification",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(owner)),
                responseType);
    }

    private ProfileResponse profile(TenantLoginResponse owner) {
        ResponseEntity<ProfileResponse> response = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ProfileResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void changeEmail(TenantLoginResponse owner, String newEmail) {
        ResponseEntity<ProfileResponse> response = restTemplate.exchange(
                "/api/me",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateProfileRequest(null, null, newEmail, null, null), authHeaders(owner)),
                ProfileResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Ages a token past its expiry. expires_at is updatable=false on the entity, by design. */
    private void expire(String rawToken) {
        int rows = jdbcTemplate.update(
                "UPDATE email_verification_tokens SET expires_at = now() - interval '1 hour' "
                        + "WHERE token_hash = ?",
                sha256Hex(rawToken));
        assertThat(rows).isEqualTo(1);
    }

    private long countLiveTokensFor(TenantLoginResponse owner) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_tokens WHERE user_id = ? "
                        + "AND consumed_at IS NULL AND superseded_at IS NULL",
                Long.class, owner.user().id());
        return count == null ? 0 : count;
    }

    private long countTokensWithHash(String value) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ?", Long.class, value);
        return count == null ? 0 : count;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpHeaders authHeaders(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
