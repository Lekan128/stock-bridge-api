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
 * Signup after a setup request (LANDING_PAGE_PLAN.md, step 4): business name, WhatsApp number and a
 * password. No email, no second password, no Company ID to invent. Runs against the local
 * docker-compose Postgres, like ClientSignupIntegrationTest.
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

    @AfterEach
    void removeSetupRequests() {
        jdbc.update("DELETE FROM setup_requests WHERE whatsapp = ?", e164);
    }

    @Test
    void aShopSignsUpWithItsWhatsAppNumberAndLogsInWithItHoweverItIsTyped() {
        ResponseEntity<TenantLoginResponse> response = signup(
                new ClientSignupRequest("Mama Tee Stores " + unique, null, null, PASSWORD, null, local, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().user().username()).isEqualTo(e164);
        assertThat(response.getBody().user().role()).isEqualTo("OWNER");
        String companyId = response.getBody().user().clientIdentifier();
        assertThat(companyId).isEqualTo("mama-tee-stores-" + unique);

        for (String typed : new String[] {local, e164, "0803 " + local.substring(4, 7) + " " + local.substring(7), "234" + local.substring(1)}) {
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
        assertThat(owner.get("email")).isNull();
        assertThat(owner.get("admin_contact_email")).isNull();
        assertThat(owner).containsEntry("phone", e164).containsEntry("client_phone", e164);
    }

    @Test
    void aGeneratedCompanyIdNeverCollidesWithAnotherShopOfTheSameName() {
        String name = "Bola Provisions " + unique;
        String first = signup(new ClientSignupRequest(name, null, null, PASSWORD, null, local, null))
                .getBody().user().clientIdentifier();
        String otherNumber = "0806" + local.substring(4);
        ResponseEntity<TenantLoginResponse> second =
                signup(new ClientSignupRequest(name, null, null, PASSWORD, null, otherNumber, null));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first).isEqualTo("bola-provisions-" + unique);
        assertThat(second.getBody().user().clientIdentifier()).isEqualTo("bola-provisions-" + unique + "-2");
    }

    @Test
    void anEmailStillWorksAndTheSecondPasswordIsOptional() {
        String email = "owner-" + unique + "@example.com";
        ResponseEntity<TenantLoginResponse> response =
                signup(new ClientSignupRequest("Email Shop " + unique, null, email, PASSWORD, null, local, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().user().username()).isEqualTo(email);

        // The owner may log in with either: the email (the username) or the WhatsApp number.
        String companyId = response.getBody().user().clientIdentifier();
        for (String typed : new String[] {email, email.toUpperCase(), local, e164}) {
            assertThat(restTemplate.postForEntity("/api/auth/login", new LoginRequest(companyId, typed, PASSWORD),
                    TenantLoginResponse.class).getStatusCode()).as("logging in as %s", typed).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void neitherAnEmailNorAUsableNumberIsRefusedPlainly() {
        ResponseEntity<ApiError> none = restTemplate.postForEntity(
                "/api/clients/signup", new ClientSignupRequest("No Contact " + unique, null, null, PASSWORD, null, null, null), ApiError.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody().message()).isEqualTo("Enter your WhatsApp number or an email address.");

        ResponseEntity<ApiError> landline = restTemplate.postForEntity(
                "/api/clients/signup", new ClientSignupRequest("Landline " + unique, null, null, PASSWORD, null, "01 234 5678", null), ApiError.class);
        assertThat(landline.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(landline.getBody().message()).contains("Nigerian mobile number");
    }

    @Test
    void theSetupRequestRemembersTheAccountItBecame() {
        SetupRequestResponse setup = restTemplate.postForObject(
                "/api/public/setup-requests",
                Map.of("businessName", "Linked Shop " + unique, "whatsapp", local),
                SetupRequestResponse.class);

        TenantLoginResponse account = signup(
                new ClientSignupRequest("Linked Shop " + unique, null, null, PASSWORD, null, local, setup.id())).getBody();

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

        signup(new ClientSignupRequest("Returning Shop " + unique, null, null, PASSWORD, null, local, null));

        assertThat(jdbc.queryForObject("SELECT client_id FROM setup_requests WHERE id = ?", UUID.class, setup.id())).isNotNull();
    }

    private ResponseEntity<TenantLoginResponse> signup(ClientSignupRequest request) {
        return restTemplate.postForEntity("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
