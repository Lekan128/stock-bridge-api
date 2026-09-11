package com.procurepal_services.stock_bridge_api.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CatalogResetPreview;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CatalogResetRequest;
import java.math.BigDecimal;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Covers {@code /api/superadmin/clients/{id}/catalog-reset} end to end against the local
 * docker-compose Postgres, for the reason AuthIntegrationTest gives.
 *
 * <p>The reset exists to unstick a client whose onboarding upload went wrong, and the assertion
 * that actually matters is the last one in {@link #resetLetsTheTenantReuseTheSameSkus}: the
 * whole point of deleting rather than deactivating is that the SAME SKU can be uploaded again.
 * A soft-delete implementation passes every other test in this file and fails that one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ClientCatalogResetIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void previewReportsWhatWouldGoWithoutDeletingAnything() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Preview Reset Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "PREV-1");
        stockIn(admin, product.id(), 5);

        CatalogResetPreview preview = preview(token, client.getId());

        assertThat(preview.blocked()).isFalse();
        assertThat(preview.counts().products()).isEqualTo(1);
        assertThat(preview.counts().stockMovements()).isEqualTo(1);
        assertThat(preview.clientSlug()).isEqualTo(client.getSlug());

        // Nothing was written: the same preview still reports the same rows.
        assertThat(preview(token, client.getId()).counts().products()).isEqualTo(1);
        assertThat(countProducts(client.getId())).isEqualTo(1);
    }

    @Test
    void resetLetsTheTenantReuseTheSameSkus() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Fresh Start Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse first = createProduct(admin, "RESET-SKU-1");
        stockIn(admin, first.id(), 12);

        ResponseEntity<CatalogResetPreview> response = reset(token, client.getId(), client.getSlug());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().counts().products()).isEqualTo(1);
        assertThat(response.getBody().counts().stockMovements()).isEqualTo(1);
        assertThat(countProducts(client.getId())).isZero();

        // The account itself survives - the tenant, its users and their login.
        assertThat(clientRepository.findById(client.getId())).isPresent();
        assertThat(countUsers(client.getId())).isPositive();

        // And the reason this deletes instead of deactivating: the SKU is free again, so the
        // re-upload creates a real, visible product rather than silently updating a hidden row.
        ProductResponse second = createProduct(admin, "RESET-SKU-1");
        assertThat(second).isNotNull();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(countProducts(client.getId())).isEqualTo(1);
    }

    @Test
    void resetRefusesWhenTheSlugIsNotTypedBack() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Unconfirmed Reset Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        createProduct(admin, "KEEP-1");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/clients/" + client.getId() + "/catalog-reset",
                HttpMethod.POST,
                new HttpEntity<>(new CatalogResetRequest("not-the-phrase", false, false, false), authHeaders(token)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countProducts(client.getId())).isEqualTo(1);
    }

    @Test
    void resetRefusesWhenTheProductsHaveBeenOrdered() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Ordered Products Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "SOLD-1");
        // Inserted directly rather than driven through cart -> checkout -> payment: what is
        // under test is the order_items -> products relationship the RESTRICT protects, and
        // going through checkout would be a test of checkout.
        insertOrderLineFor(client.getId(), product.id(), product.sku());

        ResponseEntity<CatalogResetPreview> response = reset(token, client.getId(), client.getSlug());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        CatalogResetPreview body = response.getBody();
        assertThat(body.blocked()).isTrue();
        assertThat(body.blockers()).hasSize(1);
        assertThat(body.blockers().get(0).sku()).isEqualTo("SOLD-1");
        assertThat(body.message()).contains("already been ordered");
        assertThat(countProducts(client.getId())).isEqualTo(1);
    }

    @Test
    void resetIsHarmlessOnATenantThatHasUploadedNothing() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Empty Reset Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        ResponseEntity<CatalogResetPreview> response = reset(token, client.getId(), client.getSlug());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().counts().products()).isZero();
        assertThat(clientRepository.findById(client.getId())).isPresent();
    }

    @Test
    void resetRefusesAnEstablishedCustomerUntilItIsAcknowledged() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Long Standing Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "OLD-1");
        stockIn(admin, product.id(), 40);
        ageStockMovements(client.getId(), 200);

        ResponseEntity<CatalogResetPreview> refused = reset(token, client.getId(), client.getSlug());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().blockedReason()).isEqualTo(CatalogResetPreview.ESTABLISHED_CUSTOMER);
        assertThat(refused.getBody().activity().established()).isTrue();
        assertThat(refused.getBody().activity().daysActive()).isGreaterThanOrEqualTo(200);
        assertThat(refused.getBody().message()).contains("does not look like an onboarding that went wrong");
        assertThat(countProducts(client.getId())).isEqualTo(1);

        // Acknowledged, the same request goes through - the guard is against the accident, not
        // against ops ever doing this deliberately.
        ResponseEntity<CatalogResetPreview> allowed = reset(token, client.getId(), client.getSlug(), true);

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countProducts(client.getId())).isZero();
    }

    @Test
    void aNewlyOnboardedTenantIsNotTreatedAsEstablished() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Brand New Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "NEW-1");
        stockIn(admin, product.id(), 10);

        CatalogResetPreview preview = preview(token, client.getId());

        assertThat(preview.activity().established()).isFalse();
        assertThat(preview.activity().receivedOrders()).isZero();
        // No acknowledgement passed, and it still goes through.
        assertThat(reset(token, client.getId(), client.getSlug()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void backdatedOpeningStockDoesNotMakeANewTenantLookEstablished() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Backdated Stock Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "BACKDATED-1");
        stockIn(admin, product.id(), 10);
        // A bulk import of opening balances routinely carries months-old occurred_at dates. If
        // the guard read that column instead of created_at, every new client's first upload
        // would lock them out of the very reset this feature exists for.
        jdbcTemplate.update(
                "UPDATE stock_movements SET occurred_at = now() - INTERVAL '18 months' WHERE client_id = ?",
                client.getId());

        assertThat(preview(token, client.getId()).activity().established()).isFalse();
    }

    @Test
    void aTenantThatHasReceivedAnOrderIsEstablishedHoweverRecent() {
        String token = superAdminToken();
        TenantLoginResponse admin = signup("Has Received Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        createProduct(admin, "RECEIVED-1");
        insertReceivedOrderFor(client.getId());

        CatalogResetPreview preview = preview(token, client.getId());

        assertThat(preview.activity().receivedOrders()).isEqualTo(1);
        assertThat(preview.activity().established()).isTrue();
        assertThat(reset(token, client.getId(), client.getSlug()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ helpers

    private CatalogResetPreview preview(String token, UUID clientId) {
        ResponseEntity<CatalogResetPreview> response = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/catalog-reset",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                CatalogResetPreview.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<CatalogResetPreview> reset(String token, UUID clientId, String slug) {
        return reset(token, clientId, slug, false);
    }

    private ResponseEntity<CatalogResetPreview> reset(
            String token, UUID clientId, String slug, boolean acknowledgeEstablished) {
        return restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/catalog-reset",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CatalogResetRequest("delete " + slug, acknowledgeEstablished, true, true),
                        authHeaders(token)),
                CatalogResetPreview.class);
    }

    private void insertOrderLineFor(UUID clientId, UUID productId, String sku) {
        UUID orderId = UUID.randomUUID();
        // Buyer and seller are the same tenant here purely so the fixture needs one signup:
        // what the blocker reads is order_items -> products, and seller_client_id (NOT NULL
        // since V11) just has to be a real client.
        jdbcTemplate.update("""
                INSERT INTO orders (id, order_number, client_id, seller_client_id, checkout_group_id, status,
                                    payment_status, payment_method, subtotal, total)
                VALUES (?, ?, ?, ?, ?, 'PLACED', 'PENDING', 'PAY_ON_DELIVERY', 10.00, 10.00)
                """, orderId, "TEST-" + UUID.randomUUID().toString().substring(0, 8), clientId, clientId, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, product_id, product_name, product_sku, unit_price,
                                        quantity, line_total)
                VALUES (?, ?, ?, ?, 10.00, 1, 10.00)
                """, orderId, productId, "Product " + sku, sku);
    }

    /** Backdates a tenant's whole ledger so the established guard sees real tenure. */
    private void ageStockMovements(UUID clientId, int days) {
        jdbcTemplate.update(
                "UPDATE stock_movements SET created_at = now() - make_interval(days => ?) WHERE client_id = ?",
                days, clientId);
    }

    /** A delivered order for this tenant as BUYER - no order_items, so it is not also a blocker. */
    private void insertReceivedOrderFor(UUID clientId) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders (id, order_number, client_id, seller_client_id, checkout_group_id, status,
                                    payment_status, payment_method, subtotal, total)
                VALUES (?, ?, ?, ?, ?, 'RECEIVED', 'PAID', 'PAY_ON_DELIVERY', 10.00, 10.00)
                """, orderId, "RCV-" + UUID.randomUUID().toString().substring(0, 8), clientId, clientId, orderId);
    }

    private long countProducts(UUID clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE client_id = ?", Long.class, clientId);
    }

    private long countUsers(UUID clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE client_id = ?", Long.class, clientId);
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(quantity, new BigDecimal("5.00"), null),
                        authHeaders(admin.tokens().accessToken())),
                StockMutationResponse.class);
    }

    private ProductResponse createProduct(TenantLoginResponse asAdmin, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(
                        "Product " + sku, sku, null, new BigDecimal("9.99"), null, null, null, null, null),
                productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin.tokens().accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(
                        name + " " + unique.substring(0, 8), null,
                        "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(username, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
