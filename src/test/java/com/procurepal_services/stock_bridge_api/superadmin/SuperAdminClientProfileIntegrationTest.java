package com.procurepal_services.stock_bridge_api.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateClientRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * PUT /api/superadmin/clients/{id} - ProcurePal ops editing a tenant's clients
 * row, the super-admin counterpart to the tenant-facing PUT /api/company.
 *
 * Runs against the local docker-compose Postgres over the real HTTP + Spring
 * Security filter chain - see AuthIntegrationTest for why local Postgres over
 * Testcontainers. Requires `docker compose up -d` at the project root.
 *
 * The immutability case deliberately posts RAW JSON, for the reason
 * CompanyIntegrationTest gives for doing the same: UpdateClientRequest physically
 * cannot express isPlatformOwner or isActive, so building the body through the
 * record would prove nothing about what happens when a caller sends those keys
 * anyway. A hand-rolled body is the only way to reproduce what somebody would
 * actually send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SuperAdminClientProfileIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void everyEditableFieldChangesAndPersists() {
        String token = superAdminToken();
        Client client = signupClient("Editable Fields Co");

        ResponseEntity<SuperAdminClientDetail> response = update(
                token,
                client.getId(),
                new UpdateClientRequest(
                        "Ada Wholesale Limited",
                        "accounts@adawholesale.example",
                        "+2348011112222",
                        PaymentTerms.PAY_ON_DELIVERY_ALLOWED,
                        null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuperAdminClientDetail updated = response.getBody();
        assertThat(updated.name()).isEqualTo("Ada Wholesale Limited");
        assertThat(updated.adminEmail()).isEqualTo("accounts@adawholesale.example");
        assertThat(updated.phone()).isEqualTo("+2348011112222");
        assertThat(updated.paymentTerms()).isEqualTo(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
        // Omitting the slug left it alone, which is what makes the common edit safe.
        assertThat(updated.slug()).isEqualTo(client.getSlug());

        SuperAdminClientDetail reloaded = get(token, client.getId());
        assertThat(reloaded.name()).isEqualTo("Ada Wholesale Limited");
        assertThat(reloaded.adminEmail()).isEqualTo("accounts@adawholesale.example");
        assertThat(reloaded.phone()).isEqualTo("+2348011112222");
        assertThat(reloaded.paymentTerms()).isEqualTo(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
    }

    /**
     * The two surfaces have to agree, because they are two views of one row and a
     * tenant reading its own settings page right after support edited it must not
     * see something different. This is also the field-vocabulary check: adminEmail
     * here is adminEmail there.
     */
    @Test
    void whatOpsWritesIsWhatTheTenantReadsBackOnItsOwnCompanyPage() {
        String token = superAdminToken();
        TenantLoginResponse tenant = signup("Two Surfaces Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();

        update(
                token,
                client.getId(),
                new UpdateClientRequest(
                        "Seen From Both Sides Ltd",
                        "billing@bothsides.example",
                        "0803 000 1111",
                        PaymentTerms.PAY_ON_DELIVERY_ALLOWED,
                        null));

        CompanyResponse asTenant = restTemplate
                .exchange(
                        "/api/company",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant.tokens().accessToken())),
                        CompanyResponse.class)
                .getBody();

        assertThat(asTenant.name()).isEqualTo("Seen From Both Sides Ltd");
        assertThat(asTenant.adminEmail()).isEqualTo("billing@bothsides.example");
        assertThat(asTenant.phone()).isEqualTo("0803 000 1111");
        // The credit decision ops just made is visible to the checkout screen that
        // has to honour it, which is the whole reason paymentTerms is on this
        // surface and not on the tenant's.
        assertThat(asTenant.paymentTerms()).isEqualTo(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
    }

    /** Replace semantics: a phone the ops user cleared becomes NULL, not the old value. */
    @Test
    void clearingThePhoneStoresNullRatherThanKeepingTheOldValue() {
        String token = superAdminToken();
        Client client = signupClient("Clear Phone Ops Co");
        update(
                token,
                client.getId(),
                new UpdateClientRequest("Phone Holder Ltd", "ops@phoneholder.example", "0803 000 0000",
                        PaymentTerms.PREPAID, null));

        SuperAdminClientDetail cleared = update(
                        token,
                        client.getId(),
                        new UpdateClientRequest("Phone Holder Ltd", "ops@phoneholder.example", "   ",
                                PaymentTerms.PREPAID, null))
                .getBody();

        assertThat(cleared.phone()).isNull();
        assertThat(get(token, client.getId()).phone()).isNull();
    }

    /**
     * The rename the tenant surface refuses and points here for. It is proven by
     * logging in with the new identifier and failing with the old one - a rename
     * that did not move the login is not a rename, and the blast radius documented
     * on UpdateClientRequest is exactly this.
     */
    @Test
    void renamingTheSlugMovesTheLoginIdentifier() {
        String token = superAdminToken();
        TenantLoginResponse tenant = signup("Slug Rename Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();
        String newSlug = "renamed-" + UUID.randomUUID().toString().substring(0, 8);

        SuperAdminClientDetail renamed = update(
                        token,
                        client.getId(),
                        new UpdateClientRequest(
                                client.getName(), client.getAdminContactEmail(), null, PaymentTerms.PREPAID, newSlug))
                .getBody();

        assertThat(renamed.slug()).isEqualTo(newSlug);
        assertThat(loginStatus(newSlug, tenant.user().username(), PASSWORD)).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(client.getSlug(), tenant.user().username(), PASSWORD))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void renamingOntoAnotherTenantsIdentifierIsAConflict() {
        String token = superAdminToken();
        Client first = signupClient("Slug Collision One Co");
        Client second = signupClient("Slug Collision Two Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/clients/" + first.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateClientRequest(
                                first.getName(),
                                first.getAdminContactEmail(),
                                null,
                                PaymentTerms.PREPAID,
                                second.getSlug()),
                        authHeaders(token)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("already taken");
        // Neither row moved.
        assertThat(get(token, first.getId()).slug()).isEqualTo(first.getSlug());
        assertThat(get(token, second.getId()).slug()).isEqualTo(second.getSlug());
    }

    /** Re-saving an unchanged form must not report the row colliding with itself. */
    @Test
    void resubmittingTheSameSlugIsNotAConflict() {
        String token = superAdminToken();
        Client client = signupClient("Same Slug Co");

        ResponseEntity<SuperAdminClientDetail> response = update(
                token,
                client.getId(),
                new UpdateClientRequest(
                        "Unchanged Identifier Ltd",
                        client.getAdminContactEmail(),
                        null,
                        PaymentTerms.PREPAID,
                        client.getSlug()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().slug()).isEqualTo(client.getSlug());
        assertThat(response.getBody().name()).isEqualTo("Unchanged Identifier Ltd");
    }

    @Test
    void invalidInputIsRejectedWithoutChangingAnything() {
        String token = superAdminToken();
        Client client = signupClient("Ops Validation Co");
        SuperAdminClientDetail before = get(token, client.getId());

        assertRejected(token, client.getId(),
                new UpdateClientRequest("   ", "ops@example.com", null, PaymentTerms.PREPAID, null), "name");
        assertRejected(token, client.getId(),
                new UpdateClientRequest("Still A Name Ltd", "not-an-email", null, PaymentTerms.PREPAID, null),
                "adminEmail");
        // paymentTerms is required rather than optional-means-unchanged, so that a
        // form which forgets it fails loudly instead of silently revoking credit.
        assertRejected(token, client.getId(),
                new UpdateClientRequest("Still A Name Ltd", "ops@example.com", null, null, null), "paymentTerms");
        // A slug has to be typeable at a login form.
        assertRejected(token, client.getId(),
                new UpdateClientRequest("Still A Name Ltd", "ops@example.com", null, PaymentTerms.PREPAID, "Not A Slug"),
                "slug");

        SuperAdminClientDetail after = get(token, client.getId());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.adminEmail()).isEqualTo(before.adminEmail());
        assertThat(after.slug()).isEqualTo(before.slug());
        assertThat(after.paymentTerms()).isEqualTo(before.paymentTerms());
    }

    /**
     * The invariant the whole module has to keep: nothing on any REST surface can
     * make a client the marketplace operator, or un-suspend one, through this
     * endpoint. Suspension has its own endpoint precisely so a routine profile save
     * cannot do it by accident.
     */
    @Test
    void platformOwnerAndActiveCannotBeChangedThroughThisEndpoint() {
        String token = superAdminToken();
        Client client = signupClient("Immutable Ops Co");
        updateStatus(token, client.getId(), false);
        SuperAdminClientDetail before = get(token, client.getId());
        assertThat(before.active()).isFalse();
        assertThat(before.platformOwner()).isFalse();

        ResponseEntity<SuperAdminClientDetail> response = restTemplate.exchange(
                "/api/superadmin/clients/" + client.getId(),
                HttpMethod.PUT,
                jsonBody(
                        """
                        {
                          "name": "Renamed By Ops Ltd",
                          "adminEmail": "ops@renamed.example",
                          "phone": "0801 234 5678",
                          "paymentTerms": "PREPAID",
                          "isPlatformOwner": true,
                          "platformOwner": true,
                          "isActive": true,
                          "active": true
                        }
                        """,
                        token),
                SuperAdminClientDetail.class);

        // The permitted fields did change, so the request was genuinely processed -
        // this is not passing because the whole body was rejected.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Renamed By Ops Ltd");

        for (SuperAdminClientDetail after : new SuperAdminClientDetail[] {response.getBody(), get(token, client.getId())}) {
            assertThat(after.platformOwner()).isFalse();
            assertThat(after.active()).isFalse();
        }
        // And the real platform owner is still the only one, so nothing was moved
        // out from under the public catalog.
        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);
    }

    /**
     * ProcurePal's own row is editable through the same endpoint, which is what the
     * user asked for. Restored afterwards so the seeded demo tenant every other
     * suite logs into is left exactly as it was.
     */
    @Test
    void theProcurePalRowIsEditableThroughTheSameEndpoint() {
        String token = superAdminToken();
        Client platformOwner = clientRepository.findByPlatformOwnerTrue().orElseThrow();
        String originalName = platformOwner.getName();
        String originalEmail = platformOwner.getAdminContactEmail();
        String originalPhone = platformOwner.getPhone();
        PaymentTerms originalTerms = platformOwner.getPaymentTerms();

        try {
            SuperAdminClientDetail updated = update(
                            token,
                            platformOwner.getId(),
                            new UpdateClientRequest(
                                    "ProcurePal Marketplace Operations",
                                    "operations@procurepal.example",
                                    "0700 000 0000",
                                    PaymentTerms.PAY_ON_DELIVERY_ALLOWED,
                                    null))
                    .getBody();

            assertThat(updated.name()).isEqualTo("ProcurePal Marketplace Operations");
            assertThat(updated.adminEmail()).isEqualTo("operations@procurepal.example");
            assertThat(updated.phone()).isEqualTo("0700 000 0000");
            assertThat(updated.paymentTerms()).isEqualTo(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
            // Still the platform owner, and the slug the demo seed and the
            // marketplace tests rely on is untouched because the request omitted it.
            assertThat(updated.platformOwner()).isTrue();
            assertThat(updated.slug()).isEqualTo(platformOwner.getSlug());
        } finally {
            update(
                    token,
                    platformOwner.getId(),
                    new UpdateClientRequest(originalName, originalEmail, originalPhone, originalTerms, null));
        }
    }

    @Test
    void anUnknownClientIsNotFound() {
        String token = superAdminToken();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/clients/" + UUID.randomUUID(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateClientRequest("Ghost Ltd", "ghost@example.com", null, PaymentTerms.PREPAID, null),
                        authHeaders(token)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("Client not found");
    }

    /** The audience gate: an OWNER holds every tenant permission there is and still cannot reach this. */
    @Test
    void aTenantTokenIsRefusedOnTheClientUpdateEndpoint() {
        TenantLoginResponse tenant = signup("Wrong Audience Ops Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/clients/" + client.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateClientRequest("Hijacked Ltd", "attacker@example.com", null,
                                PaymentTerms.PAY_ON_DELIVERY_ALLOWED, null),
                        authHeaders(tenant.tokens().accessToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // And in particular the credit terms it tried to grant itself did not move.
        assertThat(clientRepository.findById(client.getId()).orElseThrow().getPaymentTerms())
                .isEqualTo(PaymentTerms.PREPAID);
    }

    // ------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------

    private void assertRejected(String token, UUID clientId, UpdateClientRequest request, String expectedField) {
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId,
                HttpMethod.PUT,
                new HttpEntity<>(request, authHeaders(token)),
                ApiError.class);
        assertThat(response.getStatusCode()).as(expectedField).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).as(expectedField).contains(expectedField);
    }

    private ResponseEntity<SuperAdminClientDetail> update(String token, UUID clientId, UpdateClientRequest request) {
        return restTemplate.exchange(
                "/api/superadmin/clients/" + clientId,
                HttpMethod.PUT,
                new HttpEntity<>(request, authHeaders(token)),
                SuperAdminClientDetail.class);
    }

    private SuperAdminClientDetail get(String token, UUID clientId) {
        return restTemplate
                .exchange(
                        "/api/superadmin/clients/" + clientId,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(token)),
                        SuperAdminClientDetail.class)
                .getBody();
    }

    private void updateStatus(String token, UUID clientId, boolean active) {
        restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/status",
                HttpMethod.PUT,
                jsonBody("{\"active\": " + active + "}", token),
                SuperAdminClientDetail.class);
    }

    private HttpEntity<String> jsonBody(String json, String token) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private HttpStatus loginStatus(String clientIdentifier, String username, String password) {
        return (HttpStatus) restTemplate
                .postForEntity("/api/auth/login", new LoginRequest(clientIdentifier, username, password), String.class)
                .getStatusCode();
    }

    private Client signupClient(String name) {
        return clientRepository.findBySlug(signup(name).user().clientIdentifier()).orElseThrow();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private String superAdminToken() {
        String uniqueUsername = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
