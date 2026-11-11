package com.procurepal_services.stock_bridge_api.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;

/** Quick mode's buttons, chosen by each person and kept on their account (owner, 2026-11-01). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class QuickModeTabsIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aPersonChoosesUpToFourButtonsAndCanGoBackToTheAppsChoice() {
        TenantLoginResponse login = signup();
        assertThat(me(login).quickModeTabs()).as("nobody has chosen yet").isNull();

        ResponseEntity<ProfileResponse> chosen = put(login, List.of("count", "ask", "out"), ProfileResponse.class);
        assertThat(chosen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chosen.getBody().quickModeTabs()).as("kept in quick mode's own order").containsExactly("out", "ask", "count");
        assertThat(me(login).quickModeTabs()).containsExactly("out", "ask", "count");

        assertThat(put(login, List.of("sell"), ProfileResponse.class).getBody().quickModeTabs()).containsExactly("sell");
        assertThat(put(login, List.of(), ProfileResponse.class).getBody().quickModeTabs()).as("back to the app's").isNull();
    }

    @Test
    void moreThanFourARepeatOrAButtonQuickModeDoesntHaveIsRefused() {
        TenantLoginResponse login = signup();

        ResponseEntity<String> five = put(login, List.of("sell", "in", "out", "move", "count"), String.class);
        ResponseEntity<String> twice = put(login, List.of("in", "in"), String.class);
        ResponseEntity<String> unknown = put(login, List.of("dance"), String.class);

        assertThat(five.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(five.getBody()).contains("room for 4");
        assertThat(twice.getBody()).contains("once");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(me(login).quickModeTabs()).isNull();
    }

    private TenantLoginResponse signup() {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest("Quick " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD,
                        PASSWORD),
                TenantLoginResponse.class);
    }

    private ProfileResponse me(TenantLoginResponse login) {
        return restTemplate.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(auth(login)), ProfileResponse.class).getBody();
    }

    private <T> ResponseEntity<T> put(TenantLoginResponse login, List<String> tabs, Class<T> type) {
        HttpHeaders headers = auth(login);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("tabs", tabs);
        return restTemplate.exchange("/api/me/quick-mode", HttpMethod.PUT, new HttpEntity<>(body, headers), type);
    }

    private static HttpHeaders auth(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
