package com.procurepal_services.stock_bridge_api.email.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.preferences.dto.EmailPreferencesRequest;
import com.procurepal_services.stock_bridge_api.email.preferences.dto.EmailPreferencesResponse;
import com.procurepal_services.stock_bridge_api.email.preferences.dto.UnsubscribeResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The real HTTP + Spring Security filter chain against the local docker-compose
 * Postgres - see AuthIntegrationTest for why local Postgres over Testcontainers.
 * Requires `docker compose up -d` at the project root.
 *
 * <h2>Why this has to be an integration test and not a unit test</h2>
 * Three of the properties being asserted only exist end to end. That the public
 * endpoint is reachable with NO authentication is a fact about SecurityConfig, not
 * about the controller. That the update crosses tenants is a fact about Hibernate's
 * filter being off on an unauthenticated request and the statement being native.
 * And that the authenticated route needs no new permit-all entry is precisely the
 * kind of claim that is only true until somebody proves it.
 *
 * <p>The signing key is pinned here rather than inherited from the profile so the
 * test mints tokens the running application will accept, and so it keeps working if
 * the local default ever changes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.email.unsubscribe.secret=integration-test-unsubscribe-signing-secret",
            "app.email.unsubscribe.api-base-url=https://api.procurepal.test"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class UnsubscribeIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PATH = "/api/email/unsubscribe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UnsubscribeTokenService tokenService;

    /**
     * Read the flag with plain SQL rather than through the repository: the column is
     * what the feature actually changes, and going through JPA would read it back
     * through the same tenant filter and persistence context whose behaviour is
     * under test.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ========================================================================
    // The public one-click endpoint.
    // ========================================================================

    @Test
    void aValidTokenUnsubscribesEveryUserRowHoldingTheAddress() {
        TenantLoginResponse owner = signup("Unsub Valid Co");
        String address = owner.user().username();
        assertThat(promotionalFlagFor(address)).containsExactly(true);

        ResponseEntity<UnsubscribeResponse> response = oneClickPost(tokenService.tokenFor(address));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).contains("unsubscribed");
        assertThat(promotionalFlagFor(address)).containsExactly(false);
    }

    /**
     * The endpoint's only caller is a mail provider, which sends no credential of
     * any kind. If this ever starts needing a token, one-click unsubscribe is
     * broken for every recipient on the platform and nothing else would notice.
     */
    @Test
    void succeedsWithNoAuthenticationAtAll() {
        TenantLoginResponse owner = signup("Unsub Anonymous Co");

        ResponseEntity<UnsubscribeResponse> response =
                oneClickPost(tokenService.tokenFor(owner.user().username()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The address in this token belongs to nobody. The response must be
     * indistinguishable from the successful case above - status, body, everything -
     * or the endpoint becomes a membership test against ProcurePal's customer list,
     * runnable by anyone, at any rate.
     */
    @Test
    void anAddressWithNoUserRowStillReturnsTheSameSuccess() {
        TenantLoginResponse owner = signup("Unsub Unknown Co");
        String knownAddress = owner.user().username();
        String unknownAddress = "nobody-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<UnsubscribeResponse> known = oneClickPost(tokenService.tokenFor(knownAddress));
        ResponseEntity<UnsubscribeResponse> unknown = oneClickPost(tokenService.tokenFor(unknownAddress));

        assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknown.getBody()).isEqualTo(known.getBody());
        assertThat(promotionalFlagFor(unknownAddress)).isEmpty();
    }

    /** Providers retry. Retries must be boring. */
    @Test
    void repeatedPostsAreIdempotent() {
        TenantLoginResponse owner = signup("Unsub Idempotent Co");
        String address = owner.user().username();
        String token = tokenService.tokenFor(address);

        ResponseEntity<UnsubscribeResponse> first = oneClickPost(token);
        ResponseEntity<UnsubscribeResponse> second = oneClickPost(token);
        ResponseEntity<UnsubscribeResponse> third = oneClickPost(token);

        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getBody()).isEqualTo(first.getBody());
        assertThat(promotionalFlagFor(address)).containsExactly(false);
    }

    @Test
    void aForgedTokenIsRejectedAndChangesNothing() {
        TenantLoginResponse owner = signup("Unsub Forged Co");
        String address = owner.user().username();
        // A genuine token for somebody else, with the victim's address swapped in.
        String someoneElses = tokenService.tokenFor("attacker@example.com");
        String forged = tokenService.tokenFor(address).split("\\.")[0]
                + "."
                + someoneElses.substring(someoneElses.lastIndexOf('.') + 1);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                PATH + "?token=" + forged, HttpMethod.POST, oneClickBody(), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("not valid");
        assertThat(promotionalFlagFor(address)).containsExactly(true);
    }

    @Test
    void aMissingTokenIsRejected() {
        ResponseEntity<ApiError> response =
                restTemplate.exchange(PATH, HttpMethod.POST, oneClickBody(), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * A GET must never unsubscribe anybody: link scanners, image proxies and
     * prefetchers all follow links in mail with no human involved. 405 is the
     * intended answer - see UnsubscribeController.
     */
    @Test
    void aGetDoesNotUnsubscribeAnybody() {
        TenantLoginResponse owner = signup("Unsub Get Co");
        String address = owner.user().username();

        ResponseEntity<String> response = restTemplate.getForEntity(
                PATH + "?token=" + tokenService.tokenFor(address), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(promotionalFlagFor(address)).containsExactly(true);
    }

    /**
     * The narrowing this feature is built on, asserted rather than merely documented:
     * an unsubscribe touches receive_promotional_email and NOTHING else. If it ever
     * started clearing is_email_verified, a customer who unsubscribed from marketing
     * would stop receiving the receipts for orders they pay for.
     */
    @Test
    void unsubscribingDoesNotAffectTransactionalMail() {
        TenantLoginResponse owner = signup("Unsub Transactional Co");
        String address = owner.user().username();
        jdbcTemplate.update(
                "UPDATE users SET is_email_verified = TRUE, email_verified_at = now() WHERE lower(username) = ?",
                address);

        oneClickPost(tokenService.tokenFor(address));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_email_verified, receive_promotional_email FROM users WHERE lower(username) = ?", address);
        assertThat(row.get("receive_promotional_email")).isEqualTo(false);
        // Still verified, so EmailEligibility still permits its order receipts.
        assertThat(row.get("is_email_verified")).isEqualTo(true);
    }

    /**
     * The public endpoint runs with TenantContext empty and Hibernate's tenant
     * filter disabled, and has to reach rows in tenants it was never scoped to. Two
     * separate companies whose account holders share one inbox is the case: the human
     * objected once and must be silenced in both.
     */
    @Test
    void unsubscribesTheAddressInEveryTenantThatHoldsIt() {
        String shared = "shared-" + UUID.randomUUID() + "@example.com";
        signupWithAddress("Unsub Tenant A Co", shared);
        signupWithAddress("Unsub Tenant B Co", shared);
        assertThat(promotionalFlagFor(shared)).containsExactly(true, true);

        assertThat(oneClickPost(tokenService.tokenFor(shared)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(promotionalFlagFor(shared)).containsExactly(false, false);
    }

    // ========================================================================
    // The authenticated preference toggle - the way back.
    // ========================================================================

    @Test
    void aSignedInUserCanOptBackInAfterAOneClickUnsubscribe() {
        TenantLoginResponse owner = signup("Unsub Reversible Co");
        String address = owner.user().username();
        oneClickPost(tokenService.tokenFor(address));
        assertThat(promotionalFlagFor(address)).containsExactly(false);

        ResponseEntity<EmailPreferencesResponse> response = putPreference(owner, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().receivePromotionalEmail()).isTrue();
        assertThat(promotionalFlagFor(address)).containsExactly(true);
    }

    @Test
    void aSignedInUserCanOptOutFromInsideTheApp() {
        TenantLoginResponse owner = signup("Unsub In App Co");

        ResponseEntity<EmailPreferencesResponse> response = putPreference(owner, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().receivePromotionalEmail()).isFalse();
        assertThat(promotionalFlagFor(owner.user().username())).containsExactly(false);
    }

    /**
     * The reason the request field is a boxed Boolean with @NotNull. A primitive
     * would read an empty body as false and silently unsubscribe the caller.
     */
    @Test
    void anAbsentFlagIsRejectedRatherThanReadAsAnOptOut() {
        TenantLoginResponse owner = signup("Unsub Absent Flag Co");

        HttpHeaders headers = authHeaders(owner);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/me/email-preferences", HttpMethod.PUT, new HttpEntity<>("{}", headers), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("receivePromotionalEmail");
        assertThat(promotionalFlagFor(owner.user().username())).containsExactly(true);
    }

    /**
     * Needs no SecurityConfig entry precisely because it is NOT public - this is the
     * assertion that proves the claim in EmailPreferenceController's javadoc. 403
     * rather than 401 because no AuthenticationEntryPoint is configured app-wide;
     * see ProfileIntegrationTest, which asserts the same for /api/me.
     */
    @Test
    void thePreferenceEndpointIsNotReachableWithoutAToken() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/me/email-preferences",
                HttpMethod.PUT,
                new HttpEntity<>(new EmailPreferencesRequest(true)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Consent is personal: one company's toggle cannot speak for another's. */
    @Test
    void optingBackInOnlyAffectsTheCallersOwnRow() {
        String shared = "shared-" + UUID.randomUUID() + "@example.com";
        TenantLoginResponse first = signupWithAddress("Unsub Own Row A Co", shared);
        signupWithAddress("Unsub Own Row B Co", shared);
        oneClickPost(tokenService.tokenFor(shared));
        assertThat(promotionalFlagFor(shared)).containsExactly(false, false);

        putPreference(first, true);

        // Exactly one row came back on, and it is the caller's.
        assertThat(promotionalFlagFor(shared)).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT receive_promotional_email FROM users WHERE id = ?",
                        Boolean.class,
                        first.user().id()))
                .isTrue();
    }

    // ========================================================================
    // Helpers.
    // ========================================================================

    /** Exactly what a mail provider sends: form-encoded, one constant field. */
    private ResponseEntity<UnsubscribeResponse> oneClickPost(String token) {
        return restTemplate.exchange(
                PATH + "?token=" + token, HttpMethod.POST, oneClickBody(), UnsubscribeResponse.class);
    }

    private HttpEntity<MultiValueMap<String, String>> oneClickBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("List-Unsubscribe", "One-Click");
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<EmailPreferencesResponse> putPreference(TenantLoginResponse as, boolean receive) {
        return restTemplate.exchange(
                "/api/me/email-preferences",
                HttpMethod.PUT,
                new HttpEntity<>(new EmailPreferencesRequest(receive), authHeaders(as)),
                EmailPreferencesResponse.class);
    }

    /** One entry per user row holding the address, ordered so assertions are stable. */
    private java.util.List<Boolean> promotionalFlagFor(String address) {
        return jdbcTemplate.queryForList(
                "SELECT receive_promotional_email FROM users "
                        + "WHERE lower(email) = ? OR lower(username) = ? ORDER BY created_at",
                Boolean.class,
                address,
                address);
    }

    private TenantLoginResponse signup(String name) {
        return signupWithAddress(name, "owner-" + UUID.randomUUID() + "@example.com");
    }

    private TenantLoginResponse signupWithAddress(String name, String address) {
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + UUID.randomUUID().toString().substring(0, 8), null, address, PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
