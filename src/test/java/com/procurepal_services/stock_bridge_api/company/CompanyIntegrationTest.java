package com.procurepal_services.stock_bridge_api.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.company.dto.UpdateCompanyRequest;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
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

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers. Requires `docker compose up -d` at the project root.
 *
 * The immutability cases below deliberately post RAW JSON rather than an
 * UpdateCompanyRequest: the record physically cannot express slug,
 * isPlatformOwner, paymentTerms or isActive, so building the body through it
 * would prove nothing about what happens when a caller sends those keys anyway.
 * A hand-rolled body is the only way to reproduce what an attacker would
 * actually send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CompanyIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void ownerReadsItsOwnCompany() {
        TenantLoginResponse owner = signup("Company Read Co");

        ResponseEntity<CompanyResponse> response = restTemplate.exchange(
                "/api/company", HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), CompanyResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompanyResponse company = response.getBody();
        assertThat(company).isNotNull();
        assertThat(company.id()).isNotNull();
        assertThat(company.name()).isEqualTo(owner.user().clientName());
        assertThat(company.clientIdentifier()).isEqualTo(owner.user().clientIdentifier());
        assertThat(company.adminEmail()).isEqualTo(owner.user().username());
        assertThat(company.active()).isTrue();
        assertThat(company.createdAt()).isNotNull();
        assertThat(company.updatedAt()).isNotNull();
        // The two read-only informational fields, at their safe defaults for a
        // self-service signup. Nothing on this surface can move either of them.
        assertThat(company.platformOwner()).isFalse();
        assertThat(company.paymentTerms()).isEqualTo(PaymentTerms.PREPAID);
    }

    @Test
    void ownerUpdatesTheEditableFieldsAndTheyPersist() {
        TenantLoginResponse owner = signup("Company Update Co");
        HttpHeaders auth = authHeaders(owner);

        ResponseEntity<CompanyResponse> updateResponse = restTemplate.exchange(
                "/api/company",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateCompanyRequest("Ada Foods Limited", "accounts@adafoods.example", "+2348011112222"),
                        auth),
                CompanyResponse.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompanyResponse updated = updateResponse.getBody();
        assertThat(updated.name()).isEqualTo("Ada Foods Limited");
        assertThat(updated.adminEmail()).isEqualTo("accounts@adafoods.example");
        assertThat(updated.phone()).isEqualTo("+2348011112222");
        // The login identifier is untouched by a rename - which is the entire
        // reason it is not editable here.
        assertThat(updated.clientIdentifier()).isEqualTo(owner.user().clientIdentifier());

        CompanyResponse reloaded = getCompany(auth);
        assertThat(reloaded.name()).isEqualTo("Ada Foods Limited");
        assertThat(reloaded.adminEmail()).isEqualTo("accounts@adafoods.example");
        assertThat(reloaded.phone()).isEqualTo("+2348011112222");
    }

    /** Replace semantics: a phone the user cleared becomes NULL, not the old value. */
    @Test
    void clearingThePhoneStoresNullRatherThanKeepingTheOldValue() {
        TenantLoginResponse owner = signup("Company Clear Phone Co");
        HttpHeaders auth = authHeaders(owner);
        update(auth, new UpdateCompanyRequest("Phone Holder Ltd", "ops@phoneholder.example", "0803 000 0000"));

        CompanyResponse cleared =
                update(auth, new UpdateCompanyRequest("Phone Holder Ltd", "ops@phoneholder.example", "   "))
                        .getBody();

        assertThat(cleared.phone()).isNull();
        assertThat(getCompany(auth).phone()).isNull();
    }

    /**
     * The read is open to every authenticated tenant user by design (see
     * CompanyController); the write is not. A storekeeper is the least-privileged
     * role there is, which makes it the right probe for both halves.
     */
    @Test
    void aRoleWithoutThePermissionCanReadButCannotUpdate() {
        TenantLoginResponse owner = signup("Company Storekeeper Co");
        createUser(owner, "floor-hand", "STOREKEEPER");
        HttpHeaders storekeeper = authHeaders(login(owner.user().clientIdentifier(), "floor-hand", PASSWORD));

        assertThat(getCompany(storekeeper).name()).isEqualTo(owner.user().clientName());

        ResponseEntity<String> forbidden = restTemplate.exchange(
                "/api/company",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateCompanyRequest("Hijacked Ltd", "attacker@example.com", null), storekeeper),
                String.class);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // And nothing moved.
        assertThat(getCompany(authHeaders(owner)).name()).isEqualTo(owner.user().clientName());
    }

    /**
     * The security case this whole module exists to guarantee: even the role that
     * IS allowed to edit the company cannot reach the four fields that decide
     * whether it is the marketplace operator, whether it gets credit, whether it
     * is suspended, and what its users log in with.
     */
    @Test
    void immutableFieldsCannotBeChangedThroughThisEndpoint() {
        TenantLoginResponse owner = signup("Company Immutable Co");
        HttpHeaders auth = authHeaders(owner);
        CompanyResponse before = getCompany(auth);

        ResponseEntity<CompanyResponse> response = restTemplate.exchange(
                "/api/company",
                HttpMethod.PUT,
                jsonBody(
                        """
                        {
                          "name": "Renamed Ltd",
                          "adminEmail": "owner@renamed.example",
                          "phone": "0801 234 5678",
                          "slug": "procurepal",
                          "clientIdentifier": "procurepal",
                          "isPlatformOwner": true,
                          "platformOwner": true,
                          "paymentTerms": "PAY_ON_DELIVERY_ALLOWED",
                          "isActive": false,
                          "active": false
                        }
                        """,
                        auth),
                CompanyResponse.class);

        // The permitted fields did change, so the request was genuinely processed -
        // this is not passing because the whole body was rejected.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Renamed Ltd");

        // ...and every forbidden one is echoed back at its real, unchanged value.
        for (CompanyResponse after : new CompanyResponse[] {response.getBody(), getCompany(auth)}) {
            assertThat(after.clientIdentifier()).isEqualTo(before.clientIdentifier());
            assertThat(after.platformOwner()).isFalse();
            assertThat(after.paymentTerms()).isEqualTo(PaymentTerms.PREPAID);
            assertThat(after.active()).isTrue();
        }
    }

    @Test
    void updatingWithABlankNameIsRejected() {
        TenantLoginResponse owner = signup("Company Blank Name Co");
        HttpHeaders auth = authHeaders(owner);
        String originalName = getCompany(auth).name();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateCompanyRequest("   ", "owner@example.com", null), auth),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("name");
        assertThat(getCompany(auth).name()).isEqualTo(originalName);
    }

    @Test
    void updatingWithAMalformedAdminEmailIsRejected() {
        TenantLoginResponse owner = signup("Company Bad Email Co");
        HttpHeaders auth = authHeaders(owner);
        String originalEmail = getCompany(auth).adminEmail();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateCompanyRequest("Still A Name Ltd", "not-an-email", null), auth),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("adminEmail");
        assertThat(getCompany(auth).adminEmail()).isEqualTo(originalEmail);
    }

    /**
     * Client is the one entity with no client_id and therefore no Hibernate tenant
     * filter, so isolation here rests entirely on the id coming from TenantContext.
     * This is the test that would fail if it ever came from the request instead.
     */
    @Test
    void updatingOneCompanyLeavesEveryOtherCompanyAlone() {
        TenantLoginResponse first = signup("Company Isolation One Co");
        TenantLoginResponse second = signup("Company Isolation Two Co");
        CompanyResponse secondBefore = getCompany(authHeaders(second));

        update(
                authHeaders(first),
                new UpdateCompanyRequest("Only The First Ltd", "first@isolation.example", "0800 111 1111"));

        CompanyResponse secondAfter = getCompany(authHeaders(second));
        assertThat(secondAfter.id()).isEqualTo(secondBefore.id());
        assertThat(secondAfter.name()).isEqualTo(secondBefore.name());
        assertThat(secondAfter.adminEmail()).isEqualTo(secondBefore.adminEmail());
        assertThat(secondAfter.clientIdentifier()).isEqualTo(secondBefore.clientIdentifier());
        assertThat(secondAfter.phone()).isNull();
        // And the first really was updated, so the assertions above are not passing
        // because nothing happened at all.
        assertThat(getCompany(authHeaders(first)).name()).isEqualTo("Only The First Ltd");
    }

    /**
     * 403, not 401 - no AuthenticationEntryPoint is configured, so an anonymous
     * request is an access-denied one app-wide (ProfileIntegrationTest asserts the
     * same for /api/me). The point here is that GET /api/company is not
     * accidentally public just because it carries no @PreAuthorize.
     */
    @Test
    void companyIsNotReachableWithoutAToken() {
        assertThat(restTemplate.getForEntity("/api/company", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private CompanyResponse getCompany(HttpHeaders auth) {
        return restTemplate
                .exchange("/api/company", HttpMethod.GET, new HttpEntity<>(auth), CompanyResponse.class)
                .getBody();
    }

    private ResponseEntity<CompanyResponse> update(HttpHeaders auth, UpdateCompanyRequest request) {
        return restTemplate.exchange(
                "/api/company", HttpMethod.PUT, new HttpEntity<>(request, auth), CompanyResponse.class);
    }

    private HttpEntity<String> jsonBody(String json, HttpHeaders auth) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private void createUser(TenantLoginResponse asOwner, String username, String role) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, role, null, null, null, null, null),
                        authHeaders(asOwner)),
                UserSummaryResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private TenantLoginResponse login(String clientIdentifier, String username, String password) {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(clientIdentifier, username, password), TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
