package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * Approving purchase orders (PURCHASE_ORDER_PLAN.md section 11.5, V78): someone who can only create
 * makes an order that waits; someone who can send approves it (or asks for a change) and sends it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PurchaseOrderApprovalIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    /** What a storekeeper who may only ask needs: see products and suppliers, and make orders for approval. */
    private static final Set<String> ASKER = Set.of("VIEW_PRODUCTS", "VIEW_VENDORS", "CREATE_PURCHASE_ORDER");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyRoleThatManagedInventoryCanStillSendSoNobodyLosesAnything() {
        // The built-in roles: a custom role made after V78 holds exactly the codes its owner ticked.
        Integer missing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN permissions mi ON mi.id = rp.permission_id AND mi.code = 'MANAGE_INVENTORY'
                JOIN roles r ON r.id = rp.role_id AND r.client_id IS NULL
                WHERE NOT EXISTS (SELECT 1 FROM role_permissions s JOIN permissions sp ON sp.id = s.permission_id
                                  WHERE s.role_id = rp.role_id AND sp.code = 'SEND_PURCHASE_ORDER')
                """, Integer.class);
        assertThat(missing).isZero();

        TenantLoginResponse owner = signup("Sender Co");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        ExpectedDeliveryResponse order = create(owner, priced(tony.id(), product(owner, "Rice", "AP-SND").id()));
        assertThat(order.approval()).as("an owner's order needs no approval").isNull();
        assertThat(order.waitingToBeSent()).isTrue();
    }

    @Test
    void anOrderFromSomeoneWhoCanOnlyCreateWaitsUntilItIsApprovedThenGoesToToSend() {
        TenantLoginResponse owner = signup("Approve Co");
        TenantLoginResponse bola = asker(owner, "bola");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        ProductResponse rice = product(owner, "Rice", "AP-WAIT");

        ExpectedDeliveryRequest request = new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.TEN, new BigDecimal("900"))),
                null, true, true, false, new BigDecimal("5000"), "Tony is up on rice this week");
        ExpectedDeliveryResponse order = create(bola, request);

        assertThat(order.approval().status()).isEqualTo("WAITING");
        assertThat(order.approval().note()).isEqualTo("Tony is up on rice this week");
        assertThat(order.waitingToBeSent()).as("not on To send while it waits").isFalse();
        assertThat(order.email()).as("the email it asked for waits too").isNull();
        JsonNode counts = get(owner, "/api/expected-deliveries/counts");
        assertThat(counts.get("toApprove").asLong()).isEqualTo(1);
        assertThat(counts.get("toSend").asLong()).isZero();
        assertThat(get(owner, "/api/expected-deliveries?view=TO_APPROVE").get("content")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE type = 'PO_APPROVAL_WAITING' AND link LIKE ?",
                Integer.class, "%" + order.id())).as("the owner's bell").isEqualTo(1);

        // Bola can't send it, or approve it.
        assertThat(post(bola, "/api/expected-deliveries/" + order.id() + "/shared?channel=WHATSAPP", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(bola, "/api/expected-deliveries/" + order.id() + "/approve", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Nor can the owner send it before approving.
        assertThat(post(owner, "/api/expected-deliveries/" + order.id() + "/shared?channel=WHATSAPP", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<ExpectedDeliveryResponse> approved = restTemplate.exchange(
                "/api/expected-deliveries/" + order.id() + "/approve", HttpMethod.POST, new HttpEntity<>(json(owner)),
                ExpectedDeliveryResponse.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().approval().status()).isEqualTo("APPROVED");
        assertThat(approved.getBody().approval().approvedByName()).isNotBlank();
        assertThat(approved.getBody().waitingToBeSent()).isTrue();
        assertThat(get(owner, "/api/expected-deliveries/counts").get("toSend").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE type = 'PO_APPROVAL_ANSWERED' AND link LIKE ?",
                Integer.class, "%" + order.id())).as("Bola is told").isEqualTo(1);

        assertThat(post(owner, "/api/expected-deliveries/" + order.id() + "/shared?channel=WHATSAPP", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aChangeAskedForGoesBackToWhoeverMadeItAndTheirChangeWaitsAgain() {
        TenantLoginResponse owner = signup("Change Co");
        TenantLoginResponse bola = asker(owner, "bola");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        ProductResponse rice = product(owner, "Rice", "AP-CHG");
        ExpectedDeliveryResponse order = create(bola, priced(tony.id(), rice.id()));

        assertThat(post(owner, "/api/expected-deliveries/" + order.id() + "/ask-change", Map.of("note", " ")).getStatusCode())
                .as("a change needs saying what").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<ExpectedDeliveryResponse> asked = restTemplate.exchange(
                "/api/expected-deliveries/" + order.id() + "/ask-change", HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Call Tony, he said 850 last week"), json(owner)),
                ExpectedDeliveryResponse.class);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asked.getBody().approval().status()).isEqualTo("CHANGE_ASKED");
        assertThat(asked.getBody().approval().changeNote()).isEqualTo("Call Tony, he said 850 last week");

        ResponseEntity<ExpectedDeliveryResponse> changed = restTemplate.exchange(
                "/api/expected-deliveries/" + order.id(), HttpMethod.PUT,
                new HttpEntity<>(new ExpectedDeliveryRequest(null, null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.TEN, new BigDecimal("850"))),
                        null, null, null, null, null, "Now 850"), json(bola)),
                ExpectedDeliveryResponse.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().approval().status()).isEqualTo("WAITING");
        assertThat(changed.getBody().total()).isEqualByComparingTo("8500");
    }

    @Test
    void whoeverMadeItCanTakeBackTheirOwnRequestButNotCallOffAnApprovedOrder() {
        TenantLoginResponse owner = signup("Takeback Co");
        TenantLoginResponse bola = asker(owner, "bola");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        ProductResponse rice = product(owner, "Rice", "AP-TB");

        ExpectedDeliveryResponse mine = create(bola, priced(tony.id(), rice.id()));
        assertThat(post(bola, "/api/expected-deliveries/" + mine.id() + "/cancel", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ExpectedDeliveryResponse other = create(bola, priced(tony.id(), rice.id()));
        assertThat(post(owner, "/api/expected-deliveries/" + other.id() + "/approve", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post(bola, "/api/expected-deliveries/" + other.id() + "/cancel", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private static ExpectedDeliveryRequest priced(UUID vendorId, UUID productId) {
        return new ExpectedDeliveryRequest(vendorId, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(productId, "KG", BigDecimal.TEN, new BigDecimal("900"))),
                null, true);
    }

    /** A person at the owner's company whose role can make orders but not send them. */
    private TenantLoginResponse asker(TenantLoginResponse owner, String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<RoleResponse> role = restTemplate.exchange("/api/roles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Asker " + unique, "description", "Test role", "permissionCodes", ASKER),
                        json(owner)),
                RoleResponse.class);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String username = name + "-" + unique;
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", role.getBody().id().toString());
        ResponseEntity<UserSummaryResponse> user = restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(body, json(owner)), UserSummaryResponse.class);
        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    private ResponseEntity<String> post(TenantLoginResponse who, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(who)), String.class);
    }

    private JsonNode get(TenantLoginResponse tenant, String path) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(json(tenant)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ExpectedDeliveryResponse create(TenantLoginResponse tenant, ExpectedDeliveryRequest request) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "08030000000", "tony@stores.test", null, null, null, null,
                        null, null, null, null, null), json(tenant)),
                CompanyVendorResponse.class).getBody();
    }

    private ProductResponse product(TenantLoginResponse tenant, String name, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, "KG", null, null, null), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
