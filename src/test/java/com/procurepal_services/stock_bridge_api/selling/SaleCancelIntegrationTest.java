package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
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

/** Selling till Step 3 (SELLING_TILL_PLAN.md): cancelling a sale - never erased, with a reason, asked and answered. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SaleCancelIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theCashierCancelsTheirOwnSaleInTheFirstTwoMinutesWithAReasonAndItStaysOnRecord() {
        Shop owner = shop("Cancel Alone Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID saleId = UUID.randomUUID();
        stockOut(cashier, rice, sale(2, "500", saleId, "CASH"));

        SaleResponse cancelled = send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                Map.of("reason", "WRONG_ITEM"), SaleResponse.class).getBody();

        assertThat(cancelled.cancelState()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelReason()).isEqualTo(SaleCancelReason.WRONG_ITEM);
        assertThat(cancelled.cancelledLineCount()).isEqualTo(1);
        assertThat(cancelled.cancelDecidedByName()).startsWith("cashier-");
        assertThat(onHand(owner, rice)).isEqualByComparingTo("10");
    }

    @Test
    void afterTwoMinutesTheCashierAsksAndAManagerApprovesFromTheirOwnLogin() {
        Shop owner = shop("Cancel Ask Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID saleId = UUID.randomUUID();
        stockOut(cashier, rice, sale(3, "500", saleId, "TRANSFER"));
        threeMinutesAgo(saleId);

        ResponseEntity<String> alone = send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                Map.of("reason", "CHANGED_MIND"), String.class);
        assertThat(alone.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(alone.getBody()).contains("Ask a manager");

        SaleResponse asked = send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request",
                Map.of("reason", "CHANGED_MIND"), SaleResponse.class).getBody();
        assertThat(asked.cancelState()).isEqualTo("REQUESTED");
        assertThat(asked.cancelRequestedByName()).startsWith("cashier-");
        assertThat(onHand(owner, rice)).isEqualByComparingTo("7");
        assertThat(notifications(owner, "SALE_CANCEL_WAITING")).hasSize(1);

        // The cashier can't answer their own request.
        assertThat(send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request/approve", null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        SaleResponse approved = send(owner, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request/approve", null,
                SaleResponse.class).getBody();
        assertThat(approved.cancelState()).isEqualTo("CANCELLED");
        assertThat(approved.cancelReason()).isEqualTo(SaleCancelReason.CHANGED_MIND);
        assertThat(approved.cancelRequestedByName()).startsWith("cashier-");
        assertThat(approved.cancelDecidedByName()).isNotNull().doesNotStartWith("cashier-");
        assertThat(onHand(owner, rice)).isEqualByComparingTo("10");
        assertThat(notifications(cashier, "SALE_CANCEL_ANSWERED")).hasSize(1);
    }

    @Test
    void aDeclinedRequestLeavesTheSaleStanding() {
        Shop owner = shop("Cancel Decline Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID saleId = UUID.randomUUID();
        stockOut(cashier, rice, sale(1, "500", saleId, "CASH"));
        threeMinutesAgo(saleId);
        send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request", Map.of("reason", "OTHER", "note", "Gave change wrong"),
                SaleResponse.class);

        SaleResponse declined = send(owner, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request/decline", null,
                SaleResponse.class).getBody();

        assertThat(declined.cancelState()).isEqualTo("DECLINED");
        assertThat(declined.cancelledLineCount()).isZero();
        assertThat(declined.cancelNote()).isEqualTo("Gave change wrong");
        assertThat(onHand(owner, rice)).isEqualByComparingTo("9");
        // Approving after a decline: nothing is waiting.
        assertThat(send(owner, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request/approve", null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aCashierCanNeitherCancelNorAskAboutSomeoneElsesSale() {
        Shop owner = shop("Cancel Other Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        UUID saleId = UUID.randomUUID();
        stockOut(owner, rice, sale(1, "500", saleId, "CASH"));
        Shop cashier = cashier(owner);

        assertThat(send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel", Map.of("reason", "OTHER"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(cashier, HttpMethod.POST, "/api/sales/" + saleId + "/cancel-request", Map.of("reason", "OTHER"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void someoneWhoCanCancelSalesCancelsAnySaleAnyTime() {
        Shop owner = shop("Cancel Manager Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID saleId = UUID.randomUUID();
        stockOut(cashier, rice, sale(1, "500", saleId, "CARD"));
        threeMinutesAgo(saleId);

        SaleResponse cancelled = send(owner, HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                Map.of("reason", "WRONG_AMOUNT"), SaleResponse.class).getBody();
        assertThat(cancelled.cancelState()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelRequestedByName()).isNull();
    }

    @Test
    void myOwnSalesTodayCarryHowEachStandsAndNothingAboutCost() {
        Shop owner = shop("Mine Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        stockOut(cashier, rice, sale(1, "500", kept, "CASH"));
        stockOut(cashier, rice, sale(1, "700", gone, "CARD"));
        stockOut(owner, rice, sale(1, "900", UUID.randomUUID(), "CASH"));
        send(cashier, HttpMethod.POST, "/api/sales/" + gone + "/cancel", Map.of("reason", "WRONG_ITEM"), SaleResponse.class);

        SalesReportResponse mine = get(cashier, "/api/sales/mine", SalesReportResponse.class).getBody();
        assertThat(mine.rows()).hasSize(2);
        assertThat(mine.rows()).filteredOn(row -> row.key().equals(gone.toString())).singleElement()
                .satisfies(row -> {
                    assertThat(row.cancelState()).isEqualTo("CANCELLED");
                    assertThat(row.cancelReason()).isEqualTo("WRONG_ITEM");
                });
        assertThat(mine.rows()).allSatisfy(row -> assertThat(row.totals().costAmount()).isNull());
        assertThat(mine.totals().salesAmount()).isEqualByComparingTo("500.00");

        // The owner's report names who cancelled it.
        SalesReportResponse report = get(owner, "/api/analytics/sales?groupBy=sale&cancelled=true", SalesReportResponse.class).getBody();
        assertThat(report.rows()).singleElement().satisfies(row -> assertThat(row.cancelledByName()).startsWith("cashier-"));
    }

    private void threeMinutesAgo(UUID saleId) {
        jdbcTemplate.update("UPDATE stock_movements SET created_at = created_at - INTERVAL '3 minutes' WHERE sale_id = ?", saleId);
    }

    private BigDecimal onHand(Shop shop, UUID productId) {
        return get(shop, "/api/products/" + productId, ProductResponse.class).getBody().quantityOnHand();
    }

    private List<String> notifications(Shop shop, String type) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ? AND client_id = (SELECT id FROM clients WHERE slug = ?)",
                UUID.class, shop.login().user().username(), shop.login().user().clientIdentifier());
        return jdbcTemplate.queryForList("SELECT title FROM notifications WHERE user_id = ? AND type = ?", String.class, userId, type);
    }

    // ------------------------------------------------------------------------- helpers ----

    private record Shop(TenantLoginResponse login) {
    }

    private static Map<String, Object> sale(int quantity, String unitPrice, UUID saleId, String paymentMethod) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitPrice", new BigDecimal(unitPrice));
        body.put("saleId", saleId.toString());
        if (paymentMethod != null) body.put("paymentMethod", paymentMethod);
        return body;
    }

    private void stockOut(Shop shop, UUID productId, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = send(shop, HttpMethod.POST,
                "/api/products/" + productId + "/stock/stock-out", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void stockIn(Shop shop, UUID productId, int quantity) {
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + productId + "/stock/stock-in",
                        Map.of("quantity", quantity, "unitPrice", 100), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> changePayment(Shop shop, UUID saleId, String method, Class<T> type) {
        return send(shop, HttpMethod.PUT, "/api/sales/" + saleId + "/payment", Map.of("paymentMethod", method), type);
    }

    private SaleResponse getSale(Shop shop, UUID saleId) {
        return get(shop, "/api/sales/" + saleId, SaleResponse.class).getBody();
    }

    private <T> ResponseEntity<T> send(Shop shop, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> get(Shop shop, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(shop)), type);
    }

    private UUID product(Shop shop, String name) {
        String sku = name.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, "PIECE", null, null, null), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    /** A second person with Stock in and Stock out only - no inventory management. */
    private Shop cashier(Shop owner) {
        RoleResponse role = restTemplate.exchange("/api/roles", HttpMethod.POST,
                        new HttpEntity<>(new CreateRoleRequest("Cashier " + UUID.randomUUID().toString().substring(0, 8), null,
                                Set.of("STOCK_IN", "STOCK_OUT", "VIEW_PRODUCTS")), auth(owner)),
                        RoleResponse.class)
                .getBody();
        String username = "cashier-" + UUID.randomUUID();
        restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest(username, PASSWORD, role.id(), null, null, null, null, null), auth(owner)),
                String.class);
        return new Shop(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class));
    }

    private Shop shop(String name) {
        String unique = UUID.randomUUID().toString();
        return new Shop(restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class));
    }

    private static HttpHeaders auth(Shop shop) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.login().tokens().accessToken());
        return headers;
    }
}
