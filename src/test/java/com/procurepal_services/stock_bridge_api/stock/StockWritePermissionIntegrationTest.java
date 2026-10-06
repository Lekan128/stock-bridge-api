package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateRoleRequest;
import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Stock writes are held to the permissions a user has now, not the ones in their access token
 * (Phase H, D2) - see {@link StockWritePermissionGuard}. Found by the chaos run: a storekeeper's
 * offline delivery, sent after the owner took STOCK_IN away, was still recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockWritePermissionIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aPermissionRemovedAfterSignInIsRespectedStraightAway() {
        TenantLoginResponse owner = signup("Revoke Co");
        ProductResponse product = createProduct(owner, "REVOKE-1");
        RoleResponse role = createRole(owner, Set.of("VIEW_PRODUCTS", "STOCK_IN"));
        TenantLoginResponse storekeeper = userWithRole(owner, role.id());

        assertThat(stockIn(storekeeper, product.id(), 4, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The storekeeper's access token still says STOCK_IN; their role no longer does.
        updateRole(owner, role, Set.of("VIEW_PRODUCTS"));
        ResponseEntity<String> refused = stockIn(storekeeper, product.id(), 6, UUID.randomUUID().toString());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(quantityOnHand(owner, product.id())).isEqualTo(4);
    }

    /** A write the server accepted while the user was allowed keeps its stored answer. */
    @Test
    void aReplayOfAWriteRecordedBeforeTheChangeStillGetsItsAnswer() {
        TenantLoginResponse owner = signup("Revoke Replay Co");
        ProductResponse product = createProduct(owner, "REVOKE-2");
        RoleResponse role = createRole(owner, Set.of("VIEW_PRODUCTS", "STOCK_IN"));
        TenantLoginResponse storekeeper = userWithRole(owner, role.id());
        String key = UUID.randomUUID().toString();

        assertThat(stockIn(storekeeper, product.id(), 5, key).getStatusCode()).isEqualTo(HttpStatus.OK);
        updateRole(owner, role, Set.of("VIEW_PRODUCTS"));
        ResponseEntity<String> replay = stockIn(storekeeper, product.id(), 5, key);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("true");
        assertThat(quantityOnHand(owner, product.id())).isEqualTo(5);
    }

    @Test
    void undoIsHeldToTheCurrentPermissionToo() {
        TenantLoginResponse owner = signup("Revoke Undo Co");
        ProductResponse product = createProduct(owner, "REVOKE-3");
        RoleResponse role = createRole(owner, Set.of("VIEW_PRODUCTS", "STOCK_IN", "STOCK_OUT"));
        TenantLoginResponse storekeeper = userWithRole(owner, role.id());

        ResponseEntity<StockMutationResponse> recorded = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(3, null, null), authHeaders(storekeeper)),
                StockMutationResponse.class);
        UUID movementId = recorded.getBody().movement().id();

        // Still allowed to call void (STOCK_OUT), but not to undo a stock-in any more.
        updateRole(owner, role, Set.of("VIEW_PRODUCTS", "STOCK_OUT"));
        ResponseEntity<String> refused = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/movements/" + movementId + "/void",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(storekeeper)),
                String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(quantityOnHand(owner, product.id())).isEqualTo(3);
    }

    private ResponseEntity<String> stockIn(TenantLoginResponse as, UUID productId, int quantity, String key) {
        HttpHeaders headers = authHeaders(as);
        if (key != null) {
            headers.set(StockController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, null, null), headers),
                String.class);
    }

    private RoleResponse createRole(TenantLoginResponse owner, Set<String> permissions) {
        CreateRoleRequest request = new CreateRoleRequest("Storekeeper " + UUID.randomUUID().toString().substring(0, 8), null, permissions);
        return restTemplate
                .exchange("/api/roles", HttpMethod.POST, new HttpEntity<>(request, authHeaders(owner)), RoleResponse.class)
                .getBody();
    }

    private void updateRole(TenantLoginResponse owner, RoleResponse role, Set<String> permissions) {
        ResponseEntity<RoleResponse> response = restTemplate.exchange(
                "/api/roles/" + role.id(),
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateRoleRequest(role.name(), null, permissions), authHeaders(owner)),
                RoleResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TenantLoginResponse userWithRole(TenantLoginResponse owner, UUID roleId) {
        String username = "storekeeper-" + UUID.randomUUID();
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, roleId, null, null, null, null, null),
                        authHeaders(owner)),
                String.class);
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD),
                TenantLoginResponse.class);
    }

    private int quantityOnHand(TenantLoginResponse as, UUID productId) {
        return restTemplate
                .exchange("/api/products/" + productId, HttpMethod.GET, new HttpEntity<>(authHeaders(as)), ProductResponse.class)
                .getBody()
                .quantityOnHand();
    }

    private ProductResponse createProduct(TenantLoginResponse asOwner, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest(
                                "Product " + sku, sku, null, new BigDecimal("9.99"), null, null, null, null, null),
                        productPartHeaders));
        HttpHeaders headers = authHeaders(asOwner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate
                .exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
