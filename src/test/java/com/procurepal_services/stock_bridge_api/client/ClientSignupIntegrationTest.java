package com.procurepal_services.stock_bridge_api.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest/TenantIsolationIntegrationTest
 * for why local Postgres over Testcontainers. Requires `docker compose up -d`
 * running at the project root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ClientSignupIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void signupSucceedsLogsInImmediatelyAndAllowsSubsequentLogin() {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                "Acme Corp " + unique, null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);

        ResponseEntity<TenantLoginResponse> signupResponse =
                restTemplate.postForEntity("/api/clients/signup", request, TenantLoginResponse.class);

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantLoginResponse body = signupResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.tokens().accessToken()).isNotBlank();
        assertThat(body.tokens().refreshToken()).isNotBlank();
        assertThat(body.user().username()).isEqualTo("owner-" + unique + "@example.com");
        assertThat(body.user().role()).isEqualTo("OWNER");
        assertThat(body.user().permissions())
                .containsExactlyInAnyOrder(
                        "MANAGE_USERS",
                        "MANAGE_ROLES",
                        "MANAGE_PRODUCTS",
                        "VIEW_PRODUCTS",
                        "MANAGE_INVENTORY",
                        "VIEW_ANALYTICS",
                        "BROWSE_MARKETPLACE",
                        "PLACE_ORDERS",
                        "VIEW_ORDERS",
                        "MANAGE_DELIVERY_ADDRESSES",
                        "RECEIVE_DELIVERIES",
                        "VIEW_ALL_BRANCHES",
                        "MANAGE_MARKETPLACE",
                        "MANAGE_MARKETPLACE_ORDERS",
                        "VIEW_MARKETPLACE_ANALYTICS",
                        "MANAGE_COMPANY_PROFILE",
                        // V11. The buyer-side vendor directory: procurement's record
                        // of who the company buys from. Nothing to do with being a
                        // seller - see V11__vendors.sql on the two "vendor"s.
                        "MANAGE_VENDORS",
                        "VIEW_VENDORS");
        // A self-service signup is never the marketplace operator - that flag is
        // seeded, never claimed.
        assertThat(body.user().platformOwner()).isFalse();
        // auto-suggested from the name: lowercased, hyphenated
        assertThat(body.user().clientIdentifier()).startsWith("acme-corp-");

        // The freshly created admin can log in normally afterwards, same as any
        // other user - proves the row was actually persisted with a matching hash.
        LoginRequest loginRequest =
                new LoginRequest(body.user().clientIdentifier(), "owner-" + unique + "@example.com", PASSWORD);
        ResponseEntity<TenantLoginResponse> loginResponse =
                restTemplate.postForEntity("/api/auth/login", loginRequest, TenantLoginResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().tokens().accessToken()).isNotBlank();
    }

    @Test
    void duplicateIdentifierReturns409WithHelpfulMessage() {
        String identifier = "dup-test-" + UUID.randomUUID();
        ClientSignupRequest first = new ClientSignupRequest(
                "First Co", identifier, "first-" + UUID.randomUUID() + "@example.com", PASSWORD, PASSWORD);
        ResponseEntity<TenantLoginResponse> firstResponse =
                restTemplate.postForEntity("/api/clients/signup", first, TenantLoginResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Same identifier (even different case), different name/email.
        ClientSignupRequest second = new ClientSignupRequest(
                "Second Co", identifier.toUpperCase(), "second-" + UUID.randomUUID() + "@example.com",
                PASSWORD, PASSWORD);
        ResponseEntity<ApiError> secondResponse =
                restTemplate.postForEntity("/api/clients/signup", second, ApiError.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().message()).contains("already taken");
    }

    @Test
    void passwordMismatchReturns400() {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                "Mismatch Co", null, "mismatch-" + unique + "@example.com", PASSWORD, "a-different-password");

        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/clients/signup", request, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("do not match");
    }
}
