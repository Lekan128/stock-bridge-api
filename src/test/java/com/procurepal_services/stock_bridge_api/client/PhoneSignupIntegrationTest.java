package com.procurepal_services.stock_bridge_api.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
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
 * Signup after a setup request (LANDING_PAGE_PLAN.md, step 4): business name, email, WhatsApp number
 * and a password. No second password, no Company ID to invent. The email is required (owners,
 * 2026-10-06) and is the username; the owner can log in with the WhatsApp number too, typed any
 * way. Runs against the local docker-compose Postgres, like ClientSignupIntegrationTest.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.founding.total=1000000", "app.founding.submit-limit=1000"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PhoneSignupIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private final String local = "0803" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    private final String e164 = "+234" + local.substring(1);
    private final String unique = UUID.randomUUID().toString().substring(0, 8);
    private final String email = "owner-" + unique + "@example.com";

    @AfterEach
    void removeSetupRequests() {
        jdbc.update("DELETE FROM setup_requests WHERE whatsapp = ?", e164);
    }

    @Test
    void theOwnerLogsInWithTheEmailOrTheWhatsAppNumberHoweverItIsTyped() {
        ResponseEntity<TenantLoginResponse> response = signup(
                new ClientSignupRequest("Mama Tee Stores " + unique, null, email, PASSWORD, null, local, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().user().username()).isEqualTo(email);
        assertThat(response.getBody().user().role()).isEqualTo("OWNER");
        String companyId = response.getBody().user().clientIdentifier();
        assertThat(companyId).isEqualTo("mama-tee-stores-" + unique);

        String spaced = "0803 " + local.substring(4, 7) + " " + local.substring(7);
        String plusSpaced = "+234 " + local.substring(1, 4) + " " + local.substring(4, 7) + " " + local.substring(7);
        for (String typed : new String[] {email, email.toUpperCase(), local, spaced, e164, plusSpaced, "234" + local.substring(1)}) {
            ResponseEntity<TenantLoginResponse> login = restTemplate.postForEntity(
                    "/api/auth/login", new LoginRequest(companyId, typed, PASSWORD), TenantLoginResponse.class);
            assertThat(login.getStatusCode()).as("logging in as %s", typed).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<ApiError> wrong = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(companyId, local, "not-the-password"), ApiError.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> owner = jdbc.queryForMap(
                "SELECT u.email, u.phone, c.phone AS client_phone, c.admin_contact_email FROM users u"
                        + " JOIN clients c ON c.id = u.client_id WHERE c.slug = ?", companyId);
        assertThat(owner).containsEntry("email", email).containsEntry("admin_contact_email", email)
                .containsEntry("phone", e164).containsEntry("client_phone", e164);
    }

    @Test
    void aGeneratedCompanyIdNeverCollidesWithAnotherShopOfTheSameName() {
        String name = "Bola Provisions " + unique;
        String first = signup(new ClientSignupRequest(name, null, email, PASSWORD, null, local, null))
                .getBody().user().clientIdentifier();
        ResponseEntity<TenantLoginResponse> second = signup(
                new ClientSignupRequest(name, null, "second-" + email, PASSWORD, null, "0806" + local.substring(4), null));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first).isEqualTo("bola-provisions-" + unique);
        assertThat(second.getBody().user().clientIdentifier()).isEqualTo("bola-provisions-" + unique + "-2");
    }

    @Test
    void anEmailIsRequired() {
        ResponseEntity<String> noEmail = restTemplate.postForEntity("/api/clients/signup",
                new ClientSignupRequest("No Email " + unique, null, null, PASSWORD, null, local, null), String.class);
        assertThat(noEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noEmail.getBody()).contains("Enter your email address.");
        ResponseEntity<String> badEmail = restTemplate.postForEntity("/api/clients/signup",
                new ClientSignupRequest("Bad Email " + unique, null, "not-an-email", PASSWORD, null, local, null), String.class);
        assertThat(badEmail.getBody()).contains("Enter a valid email address.");
    }

    @Test
    void theSetupRequestRemembersTheAccountItBecame() {
        SetupRequestResponse setup = restTemplate.postForObject(
                "/api/public/setup-requests",
                Map.of("businessName", "Linked Shop " + unique, "whatsapp", local),
                SetupRequestResponse.class);

        TenantLoginResponse account = signup(
                new ClientSignupRequest("Linked Shop " + unique, null, email, PASSWORD, null, local, setup.id())).getBody();

        UUID clientId = jdbc.queryForObject("SELECT client_id FROM setup_requests WHERE id = ?", UUID.class, setup.id());
        UUID expected = jdbc.queryForObject("SELECT id FROM clients WHERE slug = ?", UUID.class, account.user().clientIdentifier());
        assertThat(clientId).isEqualTo(expected);
    }

    @Test
    void withoutTheSetupIdTheShopsRequestIsFoundByItsNumber() {
        SetupRequestResponse setup = restTemplate.postForObject(
                "/api/public/setup-requests",
                Map.of("businessName", "Returning Shop " + unique, "whatsapp", local),
                SetupRequestResponse.class);

        signup(new ClientSignupRequest("Returning Shop " + unique, null, email, PASSWORD, null, local, null));

        assertThat(jdbc.queryForObject("SELECT client_id FROM setup_requests WHERE id = ?", UUID.class, setup.id())).isNotNull();
    }

    private ResponseEntity<TenantLoginResponse> signup(ClientSignupRequest request) {
        return restTemplate.postForEntity("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
