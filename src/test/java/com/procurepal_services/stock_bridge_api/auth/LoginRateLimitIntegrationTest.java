package com.procurepal_services.stock_bridge_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Wrong passwords counted (owner, 2026-11-01): three here, so the test is quick. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.login.failures-per-login=3")
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class LoginRateLimitIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void tooManyWrongPasswordsStopThatLoginForAWhileAndARightOneClearsTheCount() {
        TenantLoginResponse owner = signup();
        String company = owner.user().clientIdentifier();
        String login = owner.user().username();

        // Two wrong, then right: signed in, and the count starts again.
        assertThat(attempt(company, login, "wrong-1").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(attempt(company, login, "wrong-2").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(attempt(company, login, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (int i = 0; i < 3; i++) {
            assertThat(attempt(company, login, "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> stopped = attempt(company, login, PASSWORD);
        assertThat(stopped.getStatusCode()).as("even the right password, for now").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(stopped.getBody()).contains("Too many tries. Try again in 15 minutes.");
        assertThat(stopped.getHeaders().getFirst("Retry-After")).isNull();
        // The same login in another case is the same login.
        assertThat(attempt(company, login.toUpperCase(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // A login nobody has is counted the same way: a refusal says nothing about who has an account.
        for (int i = 0; i < 3; i++) {
            attempt(company, "nobody-here", "wrong");
        }
        assertThat(attempt(company, "nobody-here", "wrong").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> attempt(String company, String login, String password) {
        return restTemplate.postForEntity("/api/auth/login", new LoginRequest(company, login, password), String.class);
    }

    private TenantLoginResponse signup() {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest("Limit " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD,
                        PASSWORD),
                TenantLoginResponse.class);
    }
}
