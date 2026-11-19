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
import com.procurepal_services.stock_bridge_api.push.CapturingPushTransport;
import com.procurepal_services.stock_bridge_api.push.PushTestDevice;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

    @Autowired
    private CapturingPushTransport pushes;

    /** The push services, kept in memory: what would have reached each phone. */
    @TestConfiguration
    static class Pushes {
        @Bean
        @Primary
        CapturingPushTransport capturingPushTransport() {
            return new CapturingPushTransport();
        }
    }


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

    @Test
    @SuppressWarnings("unchecked")
    void aTableBillIsSentTakenOffAndPaidAndStaysOutOfSalesUntilThen() {
        Shop owner = shop("Table Shop");
        Shop waiter = cashier(owner);
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID rice = product(owner, "Fried rice");
        UUID plantain = product(owner, "Plantain");
        stockIn(owner, rice, 20);
        stockIn(owner, plantain, 20);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");

        // Tables: "Add 5" makes 1 to 5; one renamed; a name twice is refused; a waiter can't add tables.
        java.util.List<Map<String, Object>> tables = send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables",
                Map.of("count", 5), java.util.List.class).getBody();
        assertThat(tables).extracting(t -> t.get("name")).containsExactly("1", "2", "3", "4", "5");
        send(owner, HttpMethod.PATCH, "/api/tables/" + tables.get(3).get("id"), Map.of("name", "Window"), String.class);
        assertThat(send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables", Map.of("name", "window"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(waiter, HttpMethod.POST, "/api/branches/" + branch + "/tables", Map.of("count", 2), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String table1 = (String) tables.get(0).get("id");
        java.util.List<Map<String, Object>> onBranch = (java.util.List<Map<String, Object>>) ((java.util.List<Map<String, Object>>)
                get(waiter, "/api/branches", Map.class).getBody().get("branches")).get(0).get("tables");
        assertThat(onBranch).extracting(t -> t.get("name")).containsExactly("1", "2", "3", "Window", "5");

        // Two phones open Table 1: one bill.
        String bill = (String) send(waiter, HttpMethod.POST, "/api/bills", Map.of("branchId", branch, "tableId", table1), Map.class)
                .getBody().get("id");
        assertThat(send(owner, HttpMethod.POST, "/api/bills", Map.of("branchId", branch, "tableId", table1), Map.class)
                .getBody().get("id")).isEqualTo(bill);

        // Send: a dish and its side, with a note. The stock leaves now; no payment yet.
        UUID meal = UUID.randomUUID();
        Map<String, Object> dish = sale(1, "7500", UUID.fromString(bill), null);
        dish.put("billLine", Map.of("lineKey", meal.toString(), "note", "No pepper"));
        stockOut(waiter, rice, dish);
        Map<String, Object> side = sale(1, "700", UUID.fromString(bill), null);
        side.put("billLine", Map.of("lineKey", UUID.randomUUID().toString(), "withKey", meal.toString()));
        stockOut(waiter, plantain, side);
        // A bill line never carries a payment.
        Map<String, Object> paidLine = sale(1, "700", UUID.fromString(bill), "CASH");
        paidLine.put("billLine", Map.of("lineKey", UUID.randomUUID().toString()));
        assertThat(send(waiter, HttpMethod.POST, "/api/products/" + plantain + "/stock/stock-out", paidLine, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> open = get(waiter, "/api/bills/" + bill, Map.class).getBody();
        assertThat(new BigDecimal(((Map<String, Object>) open.get("summary")).get("total").toString())).isEqualByComparingTo("8200");
        assertThat((java.util.List<?>) open.get("lines")).hasSize(2);
        assertThat(get(waiter, "/api/bills?branchId=" + branch, java.util.List.class).getBody()).hasSize(1);
        // Open: not in Sales yet.
        assertThat(get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals().saleCount()).isZero();

        // The side comes back: made, sent back - it needs a reason; stock stays used, not charged.
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) open.get("lines");
        String sideLine = (String) lines.get(1).get("movementId");
        assertThat(send(waiter, HttpMethod.POST, "/api/bills/" + bill + "/lines/" + sideLine + "/take-off",
                Map.of("kind", "SENT_BACK"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> after = send(waiter, HttpMethod.POST, "/api/bills/" + bill + "/lines/" + sideLine + "/take-off",
                Map.of("kind", "SENT_BACK", "note", "Too salty"), Map.class).getBody();
        assertThat(new BigDecimal(((Map<String, Object>) after.get("summary")).get("total").toString())).isEqualByComparingTo("7500");
        assertThat(onHand(owner, plantain)).isEqualByComparingTo("19");

        // Paid in cash: a sale like any other, with its table, the note, and the side marked.
        SaleResponse receipt = send(waiter, HttpMethod.POST, "/api/bills/" + bill + "/pay", Map.of("method", "CASH"), BillService.PayResult.class)
                .getBody().sale();
        assertThat(receipt.billFor()).isEqualTo("Table 1");
        assertThat(receipt.total()).isEqualByComparingTo("7500");
        assertThat(receipt.paymentMethod()).isEqualTo(SalePaymentMethod.CASH);
        assertThat(receipt.lines()).anySatisfy(line -> assertThat(line.note()).isEqualTo("No pepper"));
        assertThat(receipt.lines()).anySatisfy(line -> {
            assertThat(line.takenOff()).isEqualTo("SENT_BACK");
            assertThat(line.cancelled()).isTrue();
            assertThat(line.withLineId()).isNotNull();
        });
        SalesTotals totals = get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals();
        assertThat(totals.saleCount()).isEqualTo(1);
        assertThat(totals.byPayment().cashAmount()).isEqualByComparingTo("7500");

        // Paid: nothing more goes on it, and Table 1 opens a fresh bill.
        Map<String, Object> late = sale(1, "7500", UUID.fromString(bill), null);
        late.put("billLine", Map.of("lineKey", UUID.randomUUID().toString()));
        assertThat(send(waiter, HttpMethod.POST, "/api/products/" + rice + "/stock/stock-out", late, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(waiter, "/api/bills?branchId=" + branch, java.util.List.class).getBody()).isEmpty();
        String next = (String) send(waiter, HttpMethod.POST, "/api/bills", Map.of("branchId", branch, "tableId", table1), Map.class)
                .getBody().get("id");
        assertThat(next).isNotEqualTo(bill);

        // Not made yet: the stock goes back. Then the empty bill is closed, not paid.
        Map<String, Object> second = sale(1, "7500", UUID.fromString(next), null);
        second.put("billLine", Map.of("lineKey", UUID.randomUUID().toString()));
        stockOut(waiter, rice, second);
        assertThat(onHand(owner, rice)).isEqualByComparingTo("18");
        String secondLine = (String) ((java.util.List<Map<String, Object>>) get(waiter, "/api/bills/" + next, Map.class).getBody()
                .get("lines")).get(0).get("movementId");
        send(waiter, HttpMethod.POST, "/api/bills/" + next + "/lines/" + secondLine + "/take-off",
                Map.of("kind", "NOT_MADE", "note", "Changed their mind"), Map.class);
        assertThat(onHand(owner, rice)).isEqualByComparingTo("19");
        assertThat(send(waiter, HttpMethod.POST, "/api/bills/" + next + "/pay", Map.of("method", "CASH"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(waiter, HttpMethod.POST, "/api/bills/" + next + "/close", null, Map.class).getBody().get("status"))
                .isEqualTo("CANCELLED");

        // A bill for a name (a bar tab).
        assertThat(send(waiter, HttpMethod.POST, "/api/bills", Map.of("branchId", branch, "name", "Tunde"), Map.class)
                .getBody().get("label")).isEqualTo("Tunde");
    }

    @Test
    @SuppressWarnings("unchecked")
    void chargesAndTaxesAreWorkedOutOnTheFoodAndDrinksAndKeptWithEachSale() {
        Shop owner = shop("Charges Shop");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 50);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");

        // VAT is there to start with, off. Switched on, added on top of every sale; a 10% service charge and an included
        // N2,000 cover charge on table bills only.
        java.util.List<Map<String, Object>> charges = get(owner, "/api/branches/" + branch + "/charges", java.util.List.class).getBody();
        assertThat(charges).extracting(c -> c.get("name")).containsExactly("VAT");
        assertThat(charges.get(0).get("active")).isEqualTo(false);
        send(owner, HttpMethod.PATCH, "/api/charges/" + charges.get(0).get("id"), Map.of("active", true), String.class);
        send(owner, HttpMethod.POST, "/api/branches/" + branch + "/charges",
                Map.of("name", "Service charge", "kind", "PERCENT", "value", 10, "applies", "TABLE_BILLS"), String.class);
        send(owner, HttpMethod.POST, "/api/branches/" + branch + "/charges",
                Map.of("name", "Cover", "kind", "AMOUNT", "value", 2000, "included", true, "applies", "TABLE_BILLS"), String.class);
        assertThat(send(owner, HttpMethod.POST, "/api/branches/" + branch + "/charges",
                Map.of("name", "vat", "kind", "PERCENT", "value", 5), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // A counter sale: VAT only (service and cover are for tables). 7.5% of 5,000 is 375, on top.
        UUID counter = UUID.randomUUID();
        stockOut(owner, rice, sale(1, "3000", counter, "CASH"));
        stockOut(owner, rice, sale(1, "2000", counter, "CASH"));
        SaleResponse sold = getSale(owner, counter);
        assertThat(sold.charges()).extracting(ChargeService.SaleCharge::name).containsExactly("VAT");
        assertThat(sold.charges().get(0).amount()).isEqualByComparingTo("375");
        assertThat(sold.totalToPay()).isEqualByComparingTo("5375");
        // A line cancelled: the VAT follows the lines.
        UUID second = sold.lines().get(1).movementId();
        send(owner, HttpMethod.POST, "/api/sales/" + counter + "/cancel", Map.of("lineIds", java.util.List.of(second.toString()), "reason", "WRONG_ITEM"), String.class);
        assertThat(getSale(owner, counter).charges().get(0).amount()).isEqualByComparingTo("225");

        // A table bill: VAT, service, and the included cover. 8,200 of food: VAT 615, service 820, cover in the prices.
        java.util.List<Map<String, Object>> tables = send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables",
                Map.of("count", 1), java.util.List.class).getBody();
        String bill = (String) send(owner, HttpMethod.POST, "/api/bills",
                Map.of("branchId", branch, "tableId", tables.get(0).get("id")), Map.class).getBody().get("id");
        for (String price : java.util.List.of("7500", "700")) {
            Map<String, Object> line = sale(1, price, UUID.fromString(bill), null);
            line.put("billLine", Map.of("lineKey", UUID.randomUUID().toString()));
            stockOut(owner, rice, line);
        }
        Map<String, Object> open = get(owner, "/api/bills/" + bill, Map.class).getBody();
        assertThat(new BigDecimal(((Map<String, Object>) open.get("summary")).get("toPay").toString())).isEqualByComparingTo("9635");
        // Changed after the bill was opened but before it's paid: the bill pays what stands when it's paid.
        SaleResponse paid = send(owner, HttpMethod.POST, "/api/bills/" + bill + "/pay", Map.of("method", "CASH"), BillService.PayResult.class)
                .getBody().sale();
        assertThat(paid.total()).isEqualByComparingTo("8200");
        assertThat(paid.totalToPay()).isEqualByComparingTo("9635");
        assertThat(paid.charges()).extracting(ChargeService.SaleCharge::name).containsExactly("VAT", "Service charge", "Cover");
        assertThat(paid.charges().get(2).included()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM bill_payments WHERE bill_id = ?::uuid", BigDecimal.class, bill))
                .isEqualByComparingTo("9635");

        // Sales keeps the charges apart from the food and drinks.
        SalesTotals totals = get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals();
        // 11,200 of lines, of which the 2,000 cover was in the bill's prices.
        assertThat(totals.salesAmount()).isEqualByComparingTo("9200");
        assertThat(totals.charges()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("VAT");
            assertThat(c.amount()).isEqualByComparingTo("840");
        });
        // ...and the ways it was paid are the money that came in, charges and all: 3,225 and 9,635 in cash. Each sale says
        // what was added on top.
        assertThat(totals.byPayment().cashAmount()).isEqualByComparingTo("12860");
        SalesReportResponse list = get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody();
        assertThat(list.rows()).anySatisfy(row -> {
            assertThat(row.key()).isEqualTo(bill);
            assertThat(row.chargesOnTop()).isEqualByComparingTo("1435");
        });
        // Narrowed to a product, it's the food and drinks again.
        SalesReportResponse ofRice = get(owner, "/api/analytics/sales?groupBy=sale&productId=" + rice, SalesReportResponse.class).getBody();
        assertThat(ofRice.totals().byPayment().cashAmount()).isEqualByComparingTo("11200");
        assertThat(ofRice.totals().salesAmount()).isEqualByComparingTo("9200");
        assertThat(ofRice.rows()).allSatisfy(row -> assertThat(row.chargesOnTop()).isNull());

        // A shop with VAT in its prices: the total stays; the receipt says what's in it. 1,075 holds 75.
        send(owner, HttpMethod.PATCH, "/api/charges/" + charges.get(0).get("id"), Map.of("included", true), String.class);
        UUID shop = UUID.randomUUID();
        stockOut(owner, rice, sale(1, "1075", shop, "CASH"));
        SaleResponse inPrice = getSale(owner, shop);
        assertThat(inPrice.charges().get(0).amount()).isEqualByComparingTo("75");
        assertThat(inPrice.totalToPay()).isEqualByComparingTo("1075");
        // ...and the earlier sale keeps the VAT it was sold with.
        assertThat(getSale(owner, counter).charges().get(0).included()).isFalse();
        // Sales leaves taxes in the prices out of the food and drinks and the profit, and lists them with the rest:
        // 1,075 is 1,000 of rice (cost 100, profit 900) and 75 of VAT. What came in in cash is still 1,075 more.
        SalesReportResponse after = get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody();
        assertThat(after.totals().salesAmount()).isEqualByComparingTo("10200");
        assertThat(after.totals().grossProfit().subtract(totals.grossProfit())).isEqualByComparingTo("900");
        assertThat(after.totals().charges()).anySatisfy(c -> {
            assertThat(c.included()).isTrue();
            assertThat(c.amount()).isEqualByComparingTo("75");
        });
        assertThat(after.totals().byPayment().cashAmount()).isEqualByComparingTo("13935");
        assertThat(after.rows()).anySatisfy(row -> {
            assertThat(row.key()).isEqualTo(shop.toString());
            assertThat(row.totals().salesAmount()).isEqualByComparingTo("1000");
            assertThat(row.chargesInPrices()).isEqualByComparingTo("75");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBillIsPaidInPartsStaysOpenTillTheLastAndSalesSharesItAcrossTheWaysItWasPaid() {
        Shop owner = shop("Split Shop");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID rice = product(owner, "Rice");
        stockIn(owner, rice, 50);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        java.util.List<Map<String, Object>> tables = send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables",
                Map.of("count", 1), java.util.List.class).getBody();
        String bill = (String) send(owner, HttpMethod.POST, "/api/bills",
                Map.of("branchId", branch, "tableId", tables.get(0).get("id")), Map.class).getBody().get("id");
        Map<String, Object> line = sale(1, "9000", UUID.fromString(bill), null);
        line.put("billLine", Map.of("lineKey", UUID.randomUUID().toString()));
        stockOut(owner, rice, line);

        // Three friends: 3,000 by card, 3,000 by transfer; more than is left is refused.
        BillService.PayResult first = send(owner, HttpMethod.POST, "/api/bills/" + bill + "/pay",
                Map.of("method", "CARD", "amount", 3000), BillService.PayResult.class).getBody();
        assertThat(first.paid()).isFalse();
        assertThat(first.bill().summary().left()).isEqualByComparingTo("6000");
        assertThat(get(owner, "/api/bills?branchId=" + branch, java.util.List.class).getBody()).hasSize(1);
        send(owner, HttpMethod.POST, "/api/bills/" + bill + "/pay", Map.of("method", "TRANSFER", "amount", 3000), String.class);
        assertThat(send(owner, HttpMethod.POST, "/api/bills/" + bill + "/pay", Map.of("method", "CASH", "amount", 5000), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Not in Sales while part paid.
        assertThat(get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals().saleCount()).isZero();

        // The rest in cash: paid, SPLIT, each part on the receipt.
        BillService.PayResult last = send(owner, HttpMethod.POST, "/api/bills/" + bill + "/pay",
                Map.of("method", "CASH"), BillService.PayResult.class).getBody();
        assertThat(last.paid()).isTrue();
        assertThat(last.sale().paymentMethod()).isEqualTo(SalePaymentMethod.SPLIT);
        assertThat(last.sale().payments()).extracting(SaleResponse.Part::method)
                .containsExactly(SalePaymentMethod.CARD, SalePaymentMethod.TRANSFER, SalePaymentMethod.CASH);
        // Sales counts each part the way it came in: 3,000 each.
        SalesTotals.ByPayment by = get(owner, "/api/analytics/sales?groupBy=sale", SalesReportResponse.class).getBody().totals().byPayment();
        assertThat(by.cashAmount()).isEqualByComparingTo("3000");
        assertThat(by.cardAmount()).isEqualByComparingTo("3000");
        assertThat(by.transferAmount()).isEqualByComparingTo("3000");
        // A split bill's payments aren't changed from the sale.
        assertThat(send(owner, HttpMethod.PUT, "/api/sales/" + bill + "/payment", Map.of("paymentMethod", "CASH"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ticketsGoWhereItemsAreMadeTheAreaSaysReadyTheWaiterSaysServedAndATakenOffItemSaysDontSend() {
        Shop owner = shop("Tickets Shop");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID rice = product(owner, "Jollof rice");
        UUID chicken = product(owner, "Extra chicken");
        stockIn(owner, rice, 20);
        stockIn(owner, chicken, 20);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        java.util.List<Map<String, Object>> tables = send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables",
                Map.of("count", 5), java.util.List.class).getBody();
        String bill = (String) send(owner, HttpMethod.POST, "/api/bills",
                Map.of("branchId", branch, "tableId", tables.get(4).get("id")), Map.class).getBody().get("id");

        // Off to start: a send makes no ticket, and there's nowhere to keep them.
        Map<String, Object> before = sale(1, "7000", UUID.fromString(bill), null);
        before.put("billLine", Map.of("lineKey", UUID.randomUUID().toString(), "sendKey", UUID.randomUUID().toString()));
        stockOut(owner, rice, before);
        assertThat(get(owner, "/api/tickets/areas?branchId=" + branch, java.util.List.class).getBody()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tickets WHERE bill_id = ?::uuid", Long.class, bill)).isZero();

        // On: one Send, a Jollof with Extra chicken and a note, is one ticket with both lines.
        assertThat(send(owner, HttpMethod.PUT, "/api/branches/" + branch + "/tickets", Map.of("on", true), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID first = UUID.randomUUID();
        UUID meal = UUID.randomUUID();
        Map<String, Object> jollof = sale(1, "7000", UUID.fromString(bill), null);
        jollof.put("billLine", Map.of("lineKey", meal.toString(), "note", "No pepper", "sendKey", first.toString()));
        stockOut(owner, rice, jollof);
        Map<String, Object> side = sale(1, "2000", UUID.fromString(bill), null);
        side.put("billLine", Map.of("lineKey", UUID.randomUUID().toString(), "withKey", meal.toString(), "sendKey", first.toString()));
        stockOut(owner, chicken, side);

        java.util.List<Map<String, Object>> areas = get(owner, "/api/tickets/areas?branchId=" + branch, java.util.List.class).getBody();
        assertThat(areas).hasSize(1);
        assertThat(((Number) areas.get(0).get("waiting")).intValue()).isEqualTo(1);
        String area = (String) areas.get(0).get("id");
        java.util.List<Map<String, Object>> tickets = get(owner, "/api/tickets?areaId=" + area, java.util.List.class).getBody();
        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).get("label")).isEqualTo("Table 5");
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) tickets.get(0).get("lines");
        assertThat(lines).extracting(l -> l.get("name")).containsExactly("Jollof rice", "Extra chicken");
        assertThat(lines.get(0).get("note")).isEqualTo("No pepper");
        assertThat(lines.get(1).get("withKey")).isEqualTo(meal.toString());

        // Added later: ADDED.
        Map<String, Object> more = sale(2, "7000", UUID.fromString(bill), null);
        more.put("billLine", Map.of("lineKey", UUID.randomUUID().toString(), "sendKey", UUID.randomUUID().toString()));
        stockOut(owner, rice, more);
        tickets = get(owner, "/api/tickets?areaId=" + area, java.util.List.class).getBody();
        assertThat(tickets).extracting(t -> t.get("kind")).containsExactly("NEW", "ADDED");
        String firstTicket = (String) tickets.get(0).get("id");
        String addedTicket = (String) tickets.get(1).get("id");

        // Ready: it moves down; Not ready brings it back. Tables shows the area ready.
        tickets = send(owner, HttpMethod.PUT, "/api/tickets/" + firstTicket + "/ready", null, java.util.List.class).getBody();
        assertThat(tickets.get(tickets.size() - 1).get("id")).isEqualTo(firstTicket);
        assertThat(tickets.get(tickets.size() - 1).get("readyAt")).isNotNull();
        java.util.List<Map<String, Object>> open = get(owner, "/api/bills?branchId=" + branch, java.util.List.class).getBody();
        assertThat((java.util.List<String>) open.get(0).get("ready")).containsExactly("Store");
        assertThat(open.get(0).get("allServed")).isEqualTo(false);
        tickets = send(owner, HttpMethod.DELETE, "/api/tickets/" + firstTicket + "/ready", null, java.util.List.class).getBody();
        assertThat(tickets.get(0).get("readyAt")).isNull();

        // Taken off before it was made: the ticket it was on says so, waiting, until the area says OK; then the
        // ticket, with nothing left on it, goes.
        UUID movement = getSale(owner, UUID.fromString(bill)).lines().stream()
                .filter(l -> l.quantity().compareTo(new BigDecimal("2")) == 0).findFirst().orElseThrow().movementId();
        assertThat(send(owner, HttpMethod.POST, "/api/bills/" + bill + "/lines/" + movement + "/take-off",
                Map.of("kind", "NOT_MADE", "note", "Changed their mind"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        tickets = get(owner, "/api/tickets?areaId=" + area, java.util.List.class).getBody();
        Map<String, Object> changed = tickets.stream().filter(t -> t.get("id").equals(addedTicket)).findFirst().orElseThrow();
        assertThat(changed.get("changed")).isEqualTo(true);
        assertThat(((java.util.List<Map<String, Object>>) changed.get("lines")).get(0).get("takenOff")).isEqualTo("NOT_MADE");
        tickets = send(owner, HttpMethod.PUT, "/api/tickets/" + addedTicket + "/seen", null, java.util.List.class).getBody();
        assertThat(tickets).extracting(t -> t.get("id")).containsExactly(firstTicket);

        // Served from the bill, before the area said Ready: it counts as ready, and the table is all served.
        Map<String, Object> served = send(owner, HttpMethod.PUT, "/api/tickets/" + firstTicket + "/served", null, Map.class).getBody();
        java.util.List<Map<String, Object>> sends = (java.util.List<Map<String, Object>>) served.get("sends");
        Map<String, Object> mine = sends.stream().filter(x -> x.get("ticketId").equals(firstTicket)).findFirst().orElseThrow();
        assertThat(mine.get("servedAt")).isNotNull();
        assertThat(mine.get("readyAt")).isNotNull();
        assertThat(((Map<String, Object>) get(owner, "/api/tickets?areaId=" + area, java.util.List.class).getBody().get(0))
                .get("readyAt")).isNotNull();
        open = get(owner, "/api/bills?branchId=" + branch, java.util.List.class).getBody();
        assertThat(open.get(0).get("allServed")).isEqualTo(true);
        // Not served after all: the Ready it gave goes too.
        send(owner, HttpMethod.DELETE, "/api/tickets/" + firstTicket + "/served", null, Map.class);
        assertThat(jdbcTemplate.queryForObject("SELECT ready_at IS NULL FROM tickets WHERE id = ?::uuid", Boolean.class, firstTicket)).isTrue();

        // Someone not working in the area can't see its tickets.
        assertThat(get(cashier(owner), "/api/tickets?areaId=" + java.util.UUID.randomUUID(), String.class).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nothingIsSoldFromAnAreaThatOnlyHoldsStockAndARunOutSaysWhereThereIsSome() {
        Shop owner = shop("Store Room Shop");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID coke = product(owner, "Coke");
        stockIn(owner, coke, 60);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        java.util.List<Map<String, Object>> areas = (java.util.List<Map<String, Object>>) ((java.util.List<Map<String, Object>>)
                get(owner, "/api/branches", Map.class).getBody().get("branches")).get(0).get("areas");
        String store = (String) areas.get(0).get("id");
        // The only area: it can't stop selling.
        assertThat(send(owner, HttpMethod.PATCH, "/api/areas/" + store, Map.of("sells", false), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> bar = new java.util.HashMap<>();
        bar.put("name", "Bar");
        send(owner, HttpMethod.POST, "/api/branches/" + branch + "/areas", Map.of("areas", java.util.List.of(bar)), String.class);
        ResponseEntity<Map> changed = send(owner, HttpMethod.PATCH, "/api/areas/" + store, Map.of("sells", false), Map.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("sells")).isEqualTo(false);
        String barId = ((java.util.List<Map<String, Object>>) ((java.util.List<Map<String, Object>>)
                get(owner, "/api/branches", Map.class).getBody().get("branches")).get(0).get("areas")).stream()
                .filter(a -> "Bar".equals(a.get("name"))).findFirst().orElseThrow().get("id").toString();

        // From the Store: refused. From the Bar, which has none: says where there is some.
        Map<String, Object> fromStore = sale(1, "500", UUID.randomUUID(), "CASH");
        fromStore.put("areaId", store);
        ResponseEntity<String> refused = send(owner, HttpMethod.POST, "/api/products/" + coke + "/stock/stock-out", fromStore, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Nothing is sold from the Store");
        Map<String, Object> fromBar = sale(1, "500", UUID.randomUUID(), "CASH");
        fromBar.put("areaId", barId);
        ResponseEntity<String> none = send(owner, HttpMethod.POST, "/api/products/" + coke + "/stock/stock-out", fromBar, String.class);
        assertThat(none.getStatusCode().is4xxClientError()).isTrue();
        assertThat(none.getBody()).contains("None in the Bar.").contains("60").contains("in the Store: move some to the Bar first.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewTicketReachesThePhonesOfItsAreaAndReadyReachesTheWaiterWhoSentIt() throws Exception {
        Shop owner = shop("Ticket Push Shop");
        send(owner, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class);
        UUID rice = product(owner, "Jollof rice");
        stockIn(owner, rice, 20);
        String branch = (String) ((java.util.List<Map<String, Object>>) get(owner, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        send(owner, HttpMethod.PUT, "/api/branches/" + branch + "/tickets", Map.of("on", true), Map.class);
        java.util.List<Map<String, Object>> tables = send(owner, HttpMethod.POST, "/api/branches/" + branch + "/tables",
                Map.of("count", 2), java.util.List.class).getBody();
        Shop waiter = cashier(owner);

        // The owner records everywhere: new tickets are off until they turn them on. The waiter's are on.
        assertThat(get(owner, "/api/push/prefs", Map.class).getBody().get("TICKETS_NEW")).isEqualTo(false);
        assertThat(get(waiter, "/api/push/prefs", Map.class).getBody().get("TICKETS_READY")).isEqualTo(true);
        send(owner, HttpMethod.PUT, "/api/push/prefs", Map.of("kind", "TICKETS_NEW", "on", true), Map.class);
        PushTestDevice ownersPhone = new PushTestDevice();
        PushTestDevice waitersPhone = new PushTestDevice();
        send(owner, HttpMethod.POST, "/api/push/subscriptions", ownersPhone.subscription(), String.class);
        send(waiter, HttpMethod.POST, "/api/push/subscriptions", waitersPhone.subscription(), String.class);

        // The waiter sends two plates: one notification for the area, with both.
        String bill = (String) send(waiter, HttpMethod.POST, "/api/bills",
                Map.of("branchId", branch, "tableId", tables.get(1).get("id")), Map.class).getBody().get("id");
        String sendKey = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> plate = sale(1, "7000", UUID.fromString(bill), null);
            plate.put("billLine", Map.of("lineKey", UUID.randomUUID().toString(), "sendKey", sendKey));
            stockOut(waiter, rice, plate);
        }
        CapturingPushTransport.Sent told = pushes.to(ownersPhone.endpoint(), 1).getFirst();
        tools.jackson.databind.JsonNode message = tools.jackson.databind.json.JsonMapper.builder().build().readTree(ownersPhone.read(told.body()));
        assertThat(message.get("title").asString()).isEqualTo("Table 2 · Store · 2 items");
        assertThat(message.get("body").asString()).isEqualTo("Jollof rice, Jollof rice");
        assertThat(message.get("url").asString()).isEqualTo("/app/tickets");
        // The waiter who sent it isn't told about their own ticket.
        assertThat(pushes.to(waitersPhone.endpoint(), 0)).isEmpty();

        // Ready: the waiter is told.
        String area = (String) ((Map<String, Object>) get(owner, "/api/tickets/areas?branchId=" + branch, java.util.List.class).getBody().get(0)).get("id");
        String ticket = (String) ((Map<String, Object>) get(owner, "/api/tickets?areaId=" + area, java.util.List.class).getBody().get(0)).get("id");
        send(owner, HttpMethod.PUT, "/api/tickets/" + ticket + "/ready", null, java.util.List.class);
        CapturingPushTransport.Sent ready = pushes.to(waitersPhone.endpoint(), 1).getFirst();
        tools.jackson.databind.JsonNode readyMessage = tools.jackson.databind.json.JsonMapper.builder().build().readTree(waitersPhone.read(ready.body()));
        assertThat(readyMessage.get("title").asString()).isEqualTo("Table 2 · Store: ready");
        assertThat(readyMessage.get("url").asString()).isEqualTo("/app/sell/bill?id=" + bill);
    }

    private BigDecimal onHand(Shop shop, UUID productId) {
        return new BigDecimal(get(shop, "/api/products/" + productId, Map.class).getBody().get("quantityOnHand").toString());
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
