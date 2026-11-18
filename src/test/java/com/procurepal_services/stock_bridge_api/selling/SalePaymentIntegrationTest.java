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

    @Test
    void aTransferNamesTheAccountItCameIntoAndTheTotalsSplitByAccount() {
        Shop shop = shop("Accounts Shop");
        UUID rice = product(shop, "Rice");
        stockIn(shop, rice, 20);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        Map<String, Object> gtb = send(shop, HttpMethod.POST, "/api/branches/" + branch + "/payment-accounts",
                Map.of("bankName", "GTBank", "accountName", "Accounts Shop Ltd", "accountNumber", "0123456789"), Map.class).getBody();
        Map<String, Object> moniepoint = send(shop, HttpMethod.POST, "/api/branches/" + branch + "/payment-accounts",
                Map.of("bankName", "Moniepoint", "accountNumber", "5012345678"), Map.class).getBody();
        // The till reads them off the branch list.
        java.util.List<Map<String, Object>> listed = (java.util.List<Map<String, Object>>)
                ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches")).get(0).get("paymentAccounts");
        assertThat(listed).hasSize(2);

        UUID first = UUID.randomUUID();
        Map<String, Object> toGtb = sale(1, "3000", first, "TRANSFER");
        toGtb.put("paymentAccountId", gtb.get("id"));
        stockOut(shop, rice, toGtb);
        Map<String, Object> toMp = sale(1, "1000", UUID.randomUUID(), "TRANSFER");
        toMp.put("paymentAccountId", moniepoint.get("id"));
        stockOut(shop, rice, toMp);

        SaleResponse sale = getSale(shop, first);
        assertThat(sale.paymentAccountName()).isEqualTo("GTBank");
        SalesTotals.ByPayment paid = get(shop, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals().byPayment();
        assertThat(paid.transferAmount()).isEqualByComparingTo("4000.00");
        assertThat(paid.transferAccounts()).hasSize(2);
        assertThat(paid.transferAccounts().get(0).bankName()).isEqualTo("GTBank");
        assertThat(paid.transferAccounts().get(0).amount()).isEqualByComparingTo("3000.00");

        // Changed to the other account: the sale says so.
        assertThat(send(shop, HttpMethod.PUT, "/api/sales/" + first + "/payment",
                Map.of("paymentMethod", "TRANSFER", "paymentAccountId", moniepoint.get("id")), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getSale(shop, first).paymentAccountName()).isEqualTo("Moniepoint");

        // Switched off: no longer offered, and refused on a new sale - the old sales keep it.
        send(shop, HttpMethod.PATCH, "/api/payment-accounts/" + gtb.get("id"), Map.of("active", false), String.class);
        assertThat((java.util.List<?>) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody()
                .get("branches")).get(0).get("paymentAccounts")).hasSize(1);
        Map<String, Object> toOff = sale(1, "500", UUID.randomUUID(), "TRANSFER");
        toOff.put("paymentAccountId", gtb.get("id"));
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + rice + "/stock/stock-out", toOff, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDrawerStartsCountsAndClosesBlindAndALateCashSaleReopensIt() {
        Shop owner = shop("Drawer Shop");
        Shop cashier = cashier(owner);
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 50);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        // Off: nothing to start.
        assertThat(get(cashier, "/api/cash-days/till?branchId=" + branch, Map.class).getBody().get("countsCash")).isEqualTo(false);
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        assertThat(send(owner, HttpMethod.PUT, "/api/company/selling/counts-cash", Map.of("on", true), String.class)
                .getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, Object> day = send(cashier, HttpMethod.POST, "/api/cash-days",
                Map.of("branchId", branch, "floatAmount", 20000), Map.class).getBody();
        String dayId = (String) day.get("id");
        stockOut(cashier, rice, sale(1, "3000", UUID.randomUUID(), "CASH"));
        stockOut(cashier, rice, sale(1, "2000", UUID.randomUUID(), "TRANSFER"));
        assertThat(send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/money-out",
                Map.of("amount", 500, "kind", "COST", "note", "Diesel"), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        // A cost needs a note.
        assertThat(send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/money-out",
                Map.of("amount", 500, "kind", "COST"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The cashier closes counting 22,000: they see what they counted, never what it should be.
        Map<String, Object> closed = send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/close",
                Map.of("counted", 22000), Map.class).getBody();
        assertThat(closed.get("expected")).isNull();
        assertThat(closed.get("difference")).isNull();
        assertThat(((Number) closed.get("salesCount")).intValue()).isEqualTo(2);
        // The owner sees 20,000 + 3,000 - 500 = 22,500, so 500 short.
        Map<String, Object> seen = get(owner, "/api/cash-days/" + dayId, Map.class).getBody();
        assertThat(new BigDecimal(seen.get("expected").toString())).isEqualByComparingTo("22500");
        assertThat(new BigDecimal(seen.get("difference").toString())).isEqualByComparingTo("-500");

        // A mistake: the same cashier counts again.
        assertThat(send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/count-again",
                Map.of("counted", 22500), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new BigDecimal(get(owner, "/api/cash-days/" + dayId, Map.class).getBody().get("difference").toString()))
                .isEqualByComparingTo("0");

        // A late cash sale reopens the drawer from what was counted.
        stockOut(cashier, rice, sale(1, "1000", UUID.randomUUID(), "CASH"));
        Map<String, Object> till = get(cashier, "/api/cash-days/till?branchId=" + branch, Map.class).getBody();
        assertThat(till.get("open")).isNotNull();
        String reopened = (String) ((Map<String, Object>) till.get("open")).get("id");
        assertThat(new BigDecimal(get(owner, "/api/cash-days/" + reopened, Map.class).getBody().get("floatAmount").toString()))
                .isEqualByComparingTo("22500");
        // ...and the first count stands.
        assertThat(send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/count-again",
                Map.of("counted", 1), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClosedDrawerReopensOnlyWithinTheOwnersHours() {
        Shop owner = shop("Reopen Shop");
        Shop cashier = cashier(owner);
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 50);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        Map<String, Object> settings = send(owner, HttpMethod.PUT, "/api/company/selling/counts-cash", Map.of("on", true), Map.class).getBody();
        // 4 hours to start; the owner can choose 1 to 24, and only the owner.
        assertThat(((Number) settings.get("cashReopenHours")).intValue()).isEqualTo(4);
        assertThat(send(owner, HttpMethod.PUT, "/api/company/selling/cash-reopen-hours", Map.of("hours", 0), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(cashier, HttpMethod.PUT, "/api/company/selling/cash-reopen-hours", Map.of("hours", 8), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((Number) send(owner, HttpMethod.PUT, "/api/company/selling/cash-reopen-hours", Map.of("hours", 2), Map.class)
                .getBody().get("cashReopenHours")).intValue()).isEqualTo(2);

        String dayId = (String) send(cashier, HttpMethod.POST, "/api/cash-days", Map.of("branchId", branch, "floatAmount", 1000), Map.class)
                .getBody().get("id");
        send(cashier, HttpMethod.POST, "/api/cash-days/" + dayId + "/close", Map.of("counted", 1000), String.class);
        // Closed 3 hours ago, with 2 hours chosen: a new day - a sale (of any kind) doesn't reopen it, the till asks to start.
        jdbcTemplate.update("UPDATE cash_days SET closed_at = now() - interval '3 hours' WHERE id = ?::uuid", dayId);
        stockOut(cashier, rice, sale(1, "1000", UUID.randomUUID(), "TRANSFER"));
        Map<String, Object> till = get(cashier, "/api/cash-days/till?branchId=" + branch, Map.class).getBody();
        assertThat(till.get("open")).isNull();
        assertThat(till.get("closedToday")).isNull();
        // With 4 hours it is still the same day: a transfer sale reopens it from what was counted.
        send(owner, HttpMethod.PUT, "/api/company/selling/cash-reopen-hours", Map.of("hours", 4), String.class);
        stockOut(cashier, rice, sale(1, "1000", UUID.randomUUID(), "TRANSFER"));
        till = get(cashier, "/api/cash-days/till?branchId=" + branch, Map.class).getBody();
        assertThat(till.get("open")).isNotNull();
    }

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
