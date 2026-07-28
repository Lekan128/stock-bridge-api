package com.procurepal_services.stock_bridge_api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
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
 * Checks the seeded role -> permission matrix where it actually matters: at the
 * endpoints. Asserting the matrix rows in the database would only prove the
 * migration ran; these assert that a token issued for each role is accepted or
 * refused by the real filter chain, which is the thing the product promises.
 *
 * Requires `docker compose up -d` at the project root - see AuthIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class RolePermissionMatrixIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void financeOfficerSeesTheCatalogueAndAnalyticsButChangesNothing() {
        TenantLoginResponse owner = signup("Finance Officer Co");
        HttpHeaders finance = authHeaders(userWithRole(owner, "FINANCE_OFFICER"));

        assertStatus(HttpStatus.OK, "/api/analytics/summary", HttpMethod.GET, finance, null);
        assertStatus(HttpStatus.OK, "/api/products", HttpMethod.GET, finance, null);
        assertStatus(HttpStatus.FORBIDDEN, "/api/products/" + UUID.randomUUID(), HttpMethod.DELETE, finance, null);
        assertProductCreateForbidden(finance);
        assertStatus(
                HttpStatus.FORBIDDEN,
                "/api/products/" + UUID.randomUUID() + "/stock/stock-in",
                HttpMethod.POST,
                finance,
                new StockInRequest(1, new BigDecimal("1.00"), null));
        assertStatus(HttpStatus.FORBIDDEN, "/api/users", HttpMethod.GET, finance, null);
    }

    @Test
    void storekeeperMovesStockAndSeesTheCatalogueButNotAnalytics() {
        TenantLoginResponse owner = signup("Storekeeper Matrix Co");
        HttpHeaders storekeeper = authHeaders(userWithRole(owner, "STOREKEEPER"));

        assertStatus(HttpStatus.FORBIDDEN, "/api/analytics/summary", HttpMethod.GET, storekeeper, null);
        assertStatus(HttpStatus.FORBIDDEN, "/api/analytics/low-stock-summary", HttpMethod.GET, storekeeper, null);
        assertStatus(HttpStatus.OK, "/api/products", HttpMethod.GET, storekeeper, null);
        assertProductCreateForbidden(storekeeper);
        // Has MANAGE_INVENTORY, so this gets past authorization and dies on the
        // unknown product id instead - a 404, not a 403.
        assertStatus(
                HttpStatus.NOT_FOUND,
                "/api/products/" + UUID.randomUUID() + "/stock/stock-in",
                HttpMethod.POST,
                storekeeper,
                new StockInRequest(1, new BigDecimal("1.00"), null));
    }

    @Test
    void inventoryOfficerRunsStockAndReportingButCannotEditTheCatalogue() {
        TenantLoginResponse owner = signup("Inventory Officer Co");
        HttpHeaders inventory = authHeaders(userWithRole(owner, "INVENTORY_OFFICER"));

        assertStatus(HttpStatus.OK, "/api/analytics/summary", HttpMethod.GET, inventory, null);
        assertStatus(HttpStatus.OK, "/api/products", HttpMethod.GET, inventory, null);
        assertProductCreateForbidden(inventory);
        assertStatus(
                HttpStatus.NOT_FOUND,
                "/api/products/" + UUID.randomUUID() + "/stock/stock-in",
                HttpMethod.POST,
                inventory,
                new StockInRequest(1, new BigDecimal("1.00"), null));
        assertStatus(HttpStatus.FORBIDDEN, "/api/users", HttpMethod.GET, inventory, null);
    }

    @Test
    void procurementManagerOwnsTheCatalogueButNotUserManagement() {
        TenantLoginResponse owner = signup("Procurement Manager Co");
        HttpHeaders procurement = authHeaders(userWithRole(owner, "PROCUREMENT_MANAGER"));

        assertStatus(HttpStatus.CREATED, createProductRequest("PM-" + UUID.randomUUID(), procurement));
        assertStatus(HttpStatus.OK, "/api/analytics/summary", HttpMethod.GET, procurement, null);
        assertStatus(HttpStatus.FORBIDDEN, "/api/users", HttpMethod.GET, procurement, null);
        assertStatus(HttpStatus.FORBIDDEN, "/api/roles", HttpMethod.GET, procurement, null);
    }

    @Test
    void ownerReachesEverySurface() {
        HttpHeaders owner = authHeaders(signup("Owner Matrix Co"));

        assertStatus(HttpStatus.OK, "/api/users", HttpMethod.GET, owner, null);
        assertStatus(HttpStatus.OK, "/api/roles", HttpMethod.GET, owner, null);
        assertStatus(HttpStatus.OK, "/api/products", HttpMethod.GET, owner, null);
        assertStatus(HttpStatus.OK, "/api/analytics/summary", HttpMethod.GET, owner, null);
        assertStatus(HttpStatus.CREATED, createProductRequest("OWN-" + UUID.randomUUID(), owner));
    }

    /**
     * The one place the full seeded matrix is asserted row by row, including the
     * marketplace permissions V6 adds. The endpoint tests above prove the filter
     * chain honours the matrix; this proves the matrix itself is what §4.11 of the
     * marketplace contract specifies, which the endpoint tests cannot show while
     * the marketplace controllers live in other modules.
     */
    @Test
    void everyRoleCarriesExactlyTheSeededPermissionSet() {
        HttpHeaders owner = authHeaders(signup("Permission Matrix Co"));

        ResponseEntity<List<RoleResponse>> response = restTemplate.exchange(
                "/api/roles", HttpMethod.GET, new HttpEntity<>(owner), new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, List<String>> byRole = response.getBody().stream()
                .collect(Collectors.toMap(RoleResponse::name, RoleResponse::permissions));

        assertThat(byRole.get("OWNER"))
                .containsExactly(
                        "BROWSE_MARKETPLACE",
                        // V7. OWNER alone: editing the company's name and contact of
                        // record is an account-holder job, which is what OWNER is.
                        "MANAGE_COMPANY_PROFILE",
                        "MANAGE_DELIVERY_ADDRESSES",
                        "MANAGE_INVENTORY",
                        "MANAGE_MARKETPLACE",
                        "MANAGE_MARKETPLACE_ORDERS",
                        "MANAGE_PRODUCTS",
                        "MANAGE_ROLES",
                        "MANAGE_USERS",
                        "PLACE_ORDERS",
                        "RECEIVE_DELIVERIES",
                        "VIEW_ALL_BRANCHES",
                        "VIEW_ANALYTICS",
                        "VIEW_MARKETPLACE_ANALYTICS",
                        "VIEW_ORDERS",
                        "VIEW_PRODUCTS");
        assertThat(byRole.get("PROCUREMENT_MANAGER"))
                .containsExactly(
                        "BROWSE_MARKETPLACE",
                        "MANAGE_DELIVERY_ADDRESSES",
                        "MANAGE_INVENTORY",
                        "MANAGE_MARKETPLACE",
                        "MANAGE_MARKETPLACE_ORDERS",
                        "MANAGE_PRODUCTS",
                        "PLACE_ORDERS",
                        "RECEIVE_DELIVERIES",
                        "VIEW_ANALYTICS",
                        "VIEW_MARKETPLACE_ANALYTICS",
                        "VIEW_ORDERS",
                        "VIEW_PRODUCTS");
        assertThat(byRole.get("INVENTORY_OFFICER"))
                .containsExactly(
                        "BROWSE_MARKETPLACE",
                        "MANAGE_INVENTORY",
                        "RECEIVE_DELIVERIES",
                        "VIEW_ANALYTICS",
                        "VIEW_MARKETPLACE_ANALYTICS",
                        "VIEW_ORDERS",
                        "VIEW_PRODUCTS");
        assertThat(byRole.get("FINANCE_OFFICER"))
                .containsExactly("BROWSE_MARKETPLACE", "VIEW_ANALYTICS", "VIEW_ORDERS", "VIEW_PRODUCTS");
        // The storekeeper signs for goods but never sees spend: RECEIVE_DELIVERIES
        // without VIEW_ORDERS or PLACE_ORDERS is the whole point of the split.
        assertThat(byRole.get("STOREKEEPER"))
                .containsExactly("BROWSE_MARKETPLACE", "MANAGE_INVENTORY", "RECEIVE_DELIVERIES", "VIEW_PRODUCTS");
    }

    private void assertProductCreateForbidden(HttpHeaders auth) {
        assertStatus(HttpStatus.FORBIDDEN, createProductRequest("BLOCKED-" + UUID.randomUUID(), auth));
    }

    private void assertStatus(HttpStatus expected, HttpEntity<?> productCreateRequest) {
        ResponseEntity<String> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, productCreateRequest, String.class);
        assertThat(response.getStatusCode()).as("POST /api/products").isEqualTo(expected);
    }

    private void assertStatus(HttpStatus expected, String path, HttpMethod method, HttpHeaders auth, Object body) {
        ResponseEntity<String> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, auth), String.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(expected);
    }

    private HttpEntity<MultiValueMap<String, Object>> createProductRequest(String sku, HttpHeaders auth) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest("Product " + sku, sku, null, new BigDecimal("9.99"), null, null),
                        partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private TenantLoginResponse userWithRole(TenantLoginResponse owner, String role) {
        String username = role.toLowerCase() + "-" + UUID.randomUUID();
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, role, null, null, null, null, null),
                        authHeaders(owner)),
                UserSummaryResponse.class);
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD),
                TenantLoginResponse.class);
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
