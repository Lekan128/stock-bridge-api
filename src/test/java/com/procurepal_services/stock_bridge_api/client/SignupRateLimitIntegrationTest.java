package com.procurepal_services.stock_bridge_api.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Signup emails whatever address it is given, so it is limited per address (the limit that
 * protects a victim's inbox) and per client IP (the limit on how many companies one source can
 * mint) - see SignupProperties.
 *
 * <p>The IP half doubles as the proof that server.forward-headers-strategy works: the test
 * client is localhost, a trusted internal proxy to Tomcat's RemoteIpValve, so each request's
 * X-Forwarded-For becomes its client IP. Two different forwarded IPs must get two budgets -
 * before the setting, every request behind Render's proxy shared ONE.
 *
 * <p>The IP limit is lowered here (the local profile raises it, because every local signup is
 * localhost); the per-address limit is the real default of 3.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.signup.ip-limit=2", "app.signup.ip-window=1h"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SignupRateLimitIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void oneAddressCannotBeSignedUpMoreThanThreeTimesADay() {
        String victim = "victim-" + UUID.randomUUID() + "@example.com";

        // Each from a different IP, as a distributed flood would be - only the address limit can stop it.
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(signup(victim, freshIp()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<ApiError> refused = signupExpectingError(victim, freshIp());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(refused.getBody().message()).contains("sign in instead");
    }

    @Test
    void theIpLimitIsPerForwardedClientNotOneBucketForEveryone() {
        String busyIp = freshIp();
        assertThat(signup(freshAddress(), busyIp).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signup(freshAddress(), busyIp).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signupExpectingError(freshAddress(), busyIp).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A different visitor behind the same proxy is unaffected.
        assertThat(signup(freshAddress(), freshIp()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Render appends the real client to X-Forwarded-For; it does not replace what the client
     * sent. So a client-supplied entry sits to the LEFT, and must not buy a fresh budget.
     */
    @Test
    void aSpoofedLeftmostForwardedEntryDoesNotEscapeTheLimit() {
        String realIp = freshIp();
        assertThat(signup(freshAddress(), realIp).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signup(freshAddress(), freshIp() + ", " + realIp).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signupExpectingError(freshAddress(), freshIp() + ", " + realIp).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** A hidden field only a bot fills in. Refused before it can spend the victim address's budget. */
    @Test
    void aFilledHoneypotIsRefusedWithoutCreatingAnAccount() {
        String address = freshAddress();
        ClientSignupRequest bot = new ClientSignupRequest(
                "Bot Co " + UUID.randomUUID().toString().substring(0, 8), null, address, PASSWORD, PASSWORD,
                null, null, "https://spam.example");

        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/clients/signup", HttpMethod.POST, new HttpEntity<>(bot, forwardedFor(freshIp())), ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The real person behind that address can still sign up.
        assertThat(signup(address, freshIp()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> signup(String address, String forwardedFor) {
        return restTemplate.exchange(
                "/api/clients/signup", HttpMethod.POST, new HttpEntity<>(request(address), forwardedFor(forwardedFor)),
                String.class);
    }

    private ResponseEntity<ApiError> signupExpectingError(String address, String forwardedFor) {
        return restTemplate.exchange(
                "/api/clients/signup", HttpMethod.POST, new HttpEntity<>(request(address), forwardedFor(forwardedFor)),
                ApiError.class);
    }

    private static ClientSignupRequest request(String address) {
        return new ClientSignupRequest(
                "Limit Co " + UUID.randomUUID().toString().substring(0, 8), null, address, PASSWORD, PASSWORD);
    }

    private static HttpHeaders forwardedFor(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", value);
        return headers;
    }

    private static String freshAddress() {
        return "limit-" + UUID.randomUUID() + "@example.com";
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_IP = new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * A distinct TEST-NET-3 address (RFC 5737) per call - public-looking, so RemoteIpValve treats
     * it as the client, and never repeated, so tests sharing the limiter cannot collide.
     */
    private static String freshIp() {
        return "203.0.113." + NEXT_IP.getAndIncrement();
    }
}
