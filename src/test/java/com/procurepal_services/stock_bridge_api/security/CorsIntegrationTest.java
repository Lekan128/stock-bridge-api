package com.procurepal_services.stock_bridge_api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
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
 * Exercises the real HTTP + Spring Security filter chain (not the
 * CorsConfigurationSource bean in isolation), so it proves CORS and JWT auth
 * actually compose correctly end-to-end - see AuthIntegrationTest for why
 * local Postgres over Testcontainers. Requires `docker compose up -d` at the
 * project root.
 *
 * "local" profile's app.cors.allowed-origins default already covers
 * http://localhost:5173 (see application.yml), so no env var override is
 * needed here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CorsIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void crossOriginGetWithBearerTokenSucceedsAndEchoesAllowedOrigin() {
        TenantLoginResponse admin = signup("Cors Co");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(admin.tokens().accessToken());
        headers.set(HttpHeaders.ORIGIN, FRONTEND_ORIGIN);

        ResponseEntity<String> response =
                restTemplate.exchange("/api/products", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONTEND_ORIGIN);
        // We don't use cookies, so credentialed CORS is intentionally off.
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void preflightForTenantRouteIsPermittedWithoutAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, FRONTEND_ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization");

        ResponseEntity<String> response =
                restTemplate.exchange("/api/products", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONTEND_ORIGIN);
    }

    @Test
    void preflightForSuperAdminRouteIsPermittedWithoutAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, FRONTEND_ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/clients", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONTEND_ORIGIN);
    }

    @Test
    void disallowedOriginIsNotEchoedBack() {
        TenantLoginResponse admin = signup("Cors Reject Co");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(admin.tokens().accessToken());
        headers.set(HttpHeaders.ORIGIN, "http://evil.example.com");

        ResponseEntity<String> response =
                restTemplate.exchange("/api/products", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
