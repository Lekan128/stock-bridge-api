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

/** Selling till Step 1 (SELLING_TILL_PLAN.md): how the customer paid, against the real API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SalePaymentIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aBasketPaidInCashIsOneCashSaleAndItsFirstLineDecides() {
        Shop shop = shop("Cash Shop");
        UUID coke = product(shop, "Coke");
        UUID malt = product(shop, "Malt");
        stockIn(shop, coke, 10);
        stockIn(shop, malt, 10);
        UUID saleId = UUID.randomUUID();

        stockOut(shop, coke, sale(2, "500", saleId, "CASH"));
        // A later line of the same sale never changes how it was paid.
        stockOut(shop, malt, sale(1, "800", saleId, "CARD"));

        SaleResponse sale = getSale(shop, saleId);
        assertThat(sale.paymentMethod()).isEqualTo(SalePaymentMethod.CASH);
        assertThat(sale.total()).isEqualByComparingTo("1800.00");
    }

    @Test
    void todaysTotalsSplitByPaymentAndOldSalesAreNotRecordedNotCash() {
        Shop shop = shop("Split Shop");
        UUID rice = product(shop, "Rice");
        stockIn(shop, rice, 20);

        stockOut(shop, rice, sale(1, "1000", UUID.randomUUID(), "CASH"));
        stockOut(shop, rice, sale(1, "2000", UUID.randomUUID(), "CASH"));
        stockOut(shop, rice, sale(1, "3000", UUID.randomUUID(), "TRANSFER"));
        stockOut(shop, rice, sale(1, "4000", UUID.randomUUID(), "CARD"));
        UUID before = UUID.randomUUID();
        stockOut(shop, rice, sale(1, "500", before, null));

        SalesTotals.ByPayment paid = get(shop, "/api/sales/mine/summary", SalesTotals.class).getBody().byPayment();
        assertThat(paid.cashAmount()).isEqualByComparingTo("3000.00");
        assertThat(paid.cashSales()).isEqualTo(2);
        assertThat(paid.transferAmount()).isEqualByComparingTo("3000.00");
        assertThat(paid.cardAmount()).isEqualByComparingTo("4000.00");
        assertThat(paid.notRecordedAmount()).isEqualByComparingTo("500.00");
        assertThat(paid.notRecordedSales()).isEqualTo(1);

        SalesReportResponse report = get(shop, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody();
        assertThat(report.rows()).hasSize(5);
        assertThat(report.rows().stream().filter(row -> row.key().equals(before.toString())).findFirst().orElseThrow()
                .paymentMethod()).isNull();
        assertThat(report.rows().stream().filter(row -> "CARD".equals(row.paymentMethod())).count()).isEqualTo(1);
        assertThat(report.totals().byPayment().cashAmount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void aCancelledLineLeavesThePaymentTotals() {
        Shop shop = shop("Cancel Pay Shop");
        UUID rice = product(shop, "Rice");
        stockIn(shop, rice, 10);
        UUID saleId = UUID.randomUUID();
        stockOut(shop, rice, sale(1, "1000", saleId, "TRANSFER"));

        assertThat(send(shop, HttpMethod.POST, "/api/sales/" + saleId + "/cancel", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        SalesTotals.ByPayment paid = get(shop, "/api/sales/mine/summary", SalesTotals.class).getBody().byPayment();
        assertThat(paid.transferAmount()).isEqualByComparingTo("0.00");
        assertThat(paid.transferSales()).isZero();
    }

    @Test
    void theCashierCanFixTheirOwnWrongTapForTwoMinutesThenOnlyAManagerCan() {
        Shop owner = shop("Change Pay Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        Shop cashier = cashier(owner);
        UUID saleId = UUID.randomUUID();
        stockOut(cashier, rice, sale(1, "1000", saleId, "TRANSFER"));

        SaleResponse fixed = changePayment(cashier, saleId, "CASH", SaleResponse.class).getBody();
        assertThat(fixed.paymentMethod()).isEqualTo(SalePaymentMethod.CASH);
        assertThat(jdbcTemplate.queryForObject("SELECT payment_changed_by IS NOT NULL FROM sales WHERE id = ?",
                Boolean.class, saleId)).isTrue();

        // Three minutes later the cashier can no longer change it; the owner still can.
        jdbcTemplate.update("UPDATE stock_movements SET created_at = created_at - INTERVAL '3 minutes' WHERE sale_id = ?",
                saleId);
        ResponseEntity<String> late = changePayment(cashier, saleId, "CARD", String.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(late.getBody()).contains("first two minutes");
        assertThat(changePayment(owner, saleId, "CARD", SaleResponse.class).getBody().paymentMethod())
                .isEqualTo(SalePaymentMethod.CARD);
    }

    @Test
    void aCashierCannotChangeHowSomeoneElsesSaleWasPaid() {
        Shop owner = shop("Other Pay Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        UUID saleId = UUID.randomUUID();
        stockOut(owner, rice, sale(1, "1000", saleId, "CASH"));

        ResponseEntity<String> refused = changePayment(cashier(owner), saleId, "TRANSFER", String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getSale(owner, saleId).paymentMethod()).isEqualTo(SalePaymentMethod.CASH);
    }

    @Test
    void aSaleFromBeforePaymentsCanBeGivenOneByTheOwner() {
        Shop owner = shop("Late Pay Shop");
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 10);
        UUID saleId = UUID.randomUUID();
        stockOut(owner, rice, sale(1, "1000", saleId, null));
        assertThat(getSale(owner, saleId).paymentMethod()).isNull();

        assertThat(changePayment(owner, saleId, "CASH", SaleResponse.class).getBody().paymentMethod())
                .isEqualTo(SalePaymentMethod.CASH);
    }

    @Test
    void anotherCompanysSaleIdNeitherReadsNorTakesItsPayment() {
        Shop first = shop("First Pay Shop");
        Shop second = shop("Second Pay Shop");
        UUID a = product(first, "A");
        UUID b = product(second, "B");
        stockIn(first, a, 5);
        stockIn(second, b, 5);
        UUID saleId = UUID.randomUUID();
        stockOut(first, a, sale(1, "100", saleId, "CASH"));

        // The second company reuses the id: its line is recorded, but the first company's payment row stands.
        stockOut(second, b, sale(1, "100", saleId, "CARD"));
        assertThat(getSale(first, saleId).paymentMethod()).isEqualTo(SalePaymentMethod.CASH);
        assertThat(getSale(second, saleId).paymentMethod()).isNull();
    }

    @Test
    void tilesPutTheMostSoldFirstThenOtherPricedProducts() {
        Shop shop = shop("Tiles Shop");
        UUID water = product(shop, "Water");
        UUID coke = product(shop, "Coke");
        UUID beans = product(shop, "Beans");
        stockIn(shop, water, 10);
        stockIn(shop, coke, 10);
        stockOut(shop, coke, sale(1, "500", UUID.randomUUID(), "CASH"));
        stockOut(shop, coke, sale(1, "500", UUID.randomUUID(), "CASH"));
        stockOut(shop, water, sale(1, "300", UUID.randomUUID(), "CARD"));

        java.util.List<java.util.Map<String, Object>> tiles = restTemplate.exchange("/api/sales/tiles?limit=12",
                        HttpMethod.GET, new HttpEntity<>(auth(shop)),
                        new org.springframework.core.ParameterizedTypeReference<java.util.List<java.util.Map<String, Object>>>() {})
                .getBody();
        assertThat(tiles).extracting(tile -> tile.get("id"))
                .containsExactly(coke.toString(), water.toString(), beans.toString());

        assertThat(restTemplate.exchange("/api/sales/tiles?limit=2", HttpMethod.GET, new HttpEntity<>(auth(shop)), String.class)
                .getBody()).doesNotContain(beans.toString());
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
