package com.procurepal_services.stock_bridge_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.AuthTokens;
import com.procurepal_services.stock_bridge_api.auth.dto.LogoutRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.RefreshRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Refresh-token rotation and the lost reply - see {@code RefreshTokenService}.
 *
 * <p>"Lost" below means the test receives the response and then ignores it, which is exactly what
 * the server sees when the reply is lost on the way back: the rotation happened, and the client
 * still only holds the token it sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class RefreshTokenRotationIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String REFRESH = "/api/auth/refresh";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** The bug: the reply to a refresh is lost, the client retries with the token it holds. */
    @Test
    void aLostReplyIsRecoveredWithTheTokenTheClientStillHolds() {
        String held = signup().tokens().refreshToken();
        rotate(REFRESH, held); // reply lost

        String recovered = rotate(REFRESH, held);
        assertThat(recovered).isNotEqualTo(held);
        assertThat(refresh(REFRESH, recovered)).isEqualTo(HttpStatus.OK);
    }

    /** Rotation still means something: once the replacement is used, the old token is dead. */
    @Test
    void aRetiredTokenIsRefusedOnceItsReplacementHasBeenUsed() {
        String first = signup().tokens().refreshToken();
        String second = rotate(REFRESH, first);
        rotate(REFRESH, second);

        assertThat(refresh(REFRESH, first)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * After a recovery, using the new token retires every older one: the token the client held,
     * and the replacement that never arrived.
     */
    @Test
    void usingTheRecoveredTokenRetiresEveryOlderOne() {
        String held = signup().tokens().refreshToken();
        String undelivered = rotate(REFRESH, held);
        String recovered = rotate(REFRESH, held);

        rotate(REFRESH, recovered);

        assertThat(refresh(REFRESH, held)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(REFRESH, undelivered)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A second lost reply in a row (the signal dropped again) still recovers. */
    @Test
    void repeatedLostRepliesStillRecoverUntilTheNewTokenIsUsed() {
        String held = signup().tokens().refreshToken();
        rotate(REFRESH, held); // lost
        rotate(REFRESH, held); // lost again

        String third = rotate(REFRESH, held);
        rotate(REFRESH, third);

        assertThat(refresh(REFRESH, held)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Logging out with a stale token ends the session; neither it nor its replacement works after. */
    @Test
    void loggingOutWithAStaleTokenEndsTheWholeSession() {
        TenantLoginResponse session = signup();
        String held = session.tokens().refreshToken();
        String undelivered = rotate(REFRESH, held);

        logout("/api/auth/logout", session.tokens().accessToken(), held);

        assertThat(refresh(REFRESH, held)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(REFRESH, undelivered)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loggingOutWithTheCurrentTokenAlsoKillsAnOlderOne() {
        TenantLoginResponse session = signup();
        String older = session.tokens().refreshToken();
        String current = rotate(REFRESH, older);

        logout("/api/auth/logout", session.tokens().accessToken(), current);

        assertThat(refresh(REFRESH, current)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(REFRESH, older)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aLoggedOutTokenStaysDead() {
        TenantLoginResponse session = signup();
        logout("/api/auth/logout", session.tokens().accessToken(), session.tokens().refreshToken());

        assertThat(refresh(REFRESH, session.tokens().refreshToken())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownTokenIsRefused() {
        assertThat(refresh(REFRESH, "not-a-real-token")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Two tabs restoring the same session at the same moment: neither is logged out, and the
     * session carries on whichever tab's answer ends up stored.
     *
     * <p>Tabs share one stored token - whichever reply lands last overwrites the other - so what
     * has to hold is that EITHER returned token works as the next refresh. Each case gets its own
     * session, because using one tab's token retires the other's; checking both in sequence on one
     * session would test two devices, not two tabs, and pass or fail on thread timing.
     */
    @Test
    void twoTabsRefreshingTheSameTokenAtOnceBothStaySignedIn() throws Exception {
        for (int storedTab = 0; storedTab < 2; storedTab++) {
            List<String> tokens = refreshTwiceAtOnce(signup().tokens().refreshToken());
            assertThat(refresh(REFRESH, tokens.get(storedTab)))
                    .as("next refresh with tab %d's token", storedTab)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /** Fires two refreshes with the same token at once; both must succeed. Returns their tokens. */
    private List<String> refreshTwiceAtOnce(String shared) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<AuthTokens>>> tabs = List.of(
                    executor.submit(() -> awaitThenRefresh(start, shared)),
                    executor.submit(() -> awaitThenRefresh(start, shared)));
            start.countDown();
            List<String> tokens = new java.util.ArrayList<>();
            for (Future<ResponseEntity<AuthTokens>> tab : tabs) {
                ResponseEntity<AuthTokens> response = tab.get(20, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                tokens.add(response.getBody().refreshToken());
            }
            return tokens;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void superAdminSessionsRecoverFromALostReplyToo() {
        String username = "rot-admin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        String held = restTemplate
                .postForObject("/api/superadmin/auth/login", new SuperAdminLoginRequest(username, PASSWORD),
                        SuperAdminLoginResponse.class)
                .tokens()
                .refreshToken();
        String path = "/api/superadmin/auth/refresh";

        rotate(path, held); // lost
        String recovered = rotate(path, held);
        rotate(path, recovered);

        assertThat(refresh(path, held)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<AuthTokens> awaitThenRefresh(CountDownLatch start, String token) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        return restTemplate.postForEntity(REFRESH, new RefreshRequest(token), AuthTokens.class);
    }

    /** The status a refresh with {@code token} gets. Raw body, so a 401's {message} parses fine. */
    private HttpStatus refresh(String path, String token) {
        return HttpStatus.valueOf(
                restTemplate.postForEntity(path, new RefreshRequest(token), String.class).getStatusCode().value());
    }

    /** A refresh expected to succeed; returns the new refresh token. */
    private String rotate(String path, String token) {
        ResponseEntity<AuthTokens> response = restTemplate.postForEntity(path, new RefreshRequest(token), AuthTokens.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().refreshToken();
    }

    private void logout(String path, String accessToken, String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response =
                restTemplate.postForEntity(path, new HttpEntity<>(new LogoutRequest(refreshToken), headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("logout status %s", response.getStatusCode()).isTrue();
    }

    private TenantLoginResponse signup() {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest("Rotation Co " + unique.substring(0, 8), null, "owner-" + unique + "@example.com",
                        PASSWORD, PASSWORD, null),
                TenantLoginResponse.class);
    }
}
