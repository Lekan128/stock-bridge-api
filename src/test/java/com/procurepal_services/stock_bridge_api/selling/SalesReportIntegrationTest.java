package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportRow;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.StockWorthResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 * The Sales report against a shop whose figures are worked out by hand below - every kind of line a
 * real day has: priced, unpriced, cost unknown, below cost, below the usual price, cancelled.
 *
 * <pre>
 *   Rice  usual 200, bought 10 @ 100      Soap  usual 50, bought 10 @ 60      Oil  no price, bought 10, no cost
 *   S1  Rice 2 @ 200 = 400 (cost 200) + Soap 1 @ 50 = 50 (cost 60, below cost)
 *   S2  Rice 1 @ 150 = 150 (cost 100, below the usual 200)
 *   S3  Oil 1 @ 300 = 300 (cost unknown)
 *   S4  Rice 1, no price (cost 100)
 *   S5  Rice 3 @ 200 = 600 - cancelled
 *   ------------------------------------------------------------------------------------------
 *   4 sales, 5 lines . sales 900 . profit over the 3 lines with price and cost: 600 - 360 = 240
 *   1 unpriced . 1 cost unknown . 1 cancelled . 1 below usual . 1 below cost
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SalesReportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theTotalsMatchTheHandWorkedFigures() {
        Shop shop = seededShop();

        SalesTotals totals = report(shop, "sale").totals();

        assertThat(totals.saleCount()).isEqualTo(4);
        assertThat(totals.lineCount()).isEqualTo(5);
        assertThat(totals.salesAmount()).isEqualByComparingTo("900.00");
        assertThat(totals.profitKnownSalesAmount()).isEqualByComparingTo("600.00");
        assertThat(totals.costAmount()).isEqualByComparingTo("360.00");
        assertThat(totals.grossProfit()).isEqualByComparingTo("240.00");
        assertThat(totals.margin()).isEqualByComparingTo("0.4000");
        assertThat(totals.unpricedLineCount()).isEqualTo(1);
        assertThat(totals.unknownCostLineCount()).isEqualTo(1);
        assertThat(totals.cancelledLineCount()).isEqualTo(1);
        assertThat(totals.belowUsualPriceLineCount()).isEqualTo(1);
        assertThat(totals.belowCostLineCount()).isEqualTo(1);
    }

    @Test
    void forEveryGroupingTheFooterEqualsTheSumOfTheRows() {
        Shop shop = seededShop();

        for (String groupBy : SalesReportService.GROUPINGS) {
            SalesReportResponse report = report(shop, groupBy);
            assertThat(report.truncated()).isFalse();
            SalesTotals footer = report.totals();
            List<SalesReportRow> rows = report.rows();
            assertThat(sum(rows, SalesTotals::lineCount)).as(groupBy + " lines").isEqualTo(footer.lineCount());
            assertThat(sum(rows, SalesTotals::unpricedLineCount)).as(groupBy + " unpriced").isEqualTo(footer.unpricedLineCount());
            assertThat(sum(rows, SalesTotals::unknownCostLineCount)).as(groupBy + " no cost").isEqualTo(footer.unknownCostLineCount());
            assertThat(sum(rows, SalesTotals::cancelledLineCount)).as(groupBy + " cancelled").isEqualTo(footer.cancelledLineCount());
            assertThat(sum(rows, SalesTotals::belowUsualPriceLineCount)).as(groupBy + " below usual").isEqualTo(footer.belowUsualPriceLineCount());
            assertThat(money(rows, SalesTotals::salesAmount)).as(groupBy + " sales").isEqualByComparingTo(footer.salesAmount());
            assertThat(money(rows, SalesTotals::costAmount)).as(groupBy + " cost").isEqualByComparingTo(footer.costAmount());
            assertThat(money(rows, SalesTotals::grossProfit)).as(groupBy + " profit").isEqualByComparingTo(footer.grossProfit());
        }
    }

    @Test
    void eachSaleIsARowAndProductsShowWhatTheySold() {
        Shop shop = seededShop();

        // Five sales recorded, one of them wholly cancelled - still listed, so it can be seen.
        assertThat(report(shop, "sale").rows()).hasSize(5);

        SalesReportRow rice = report(shop, "product").rows().stream()
                .filter(row -> "Rice".equals(row.label())).findFirst().orElseThrow();
        assertThat(rice.quantity()).isEqualTo(4);
        assertThat(rice.totals().salesAmount()).isEqualByComparingTo("550.00");
        assertThat(rice.totals().cancelledLineCount()).isEqualTo(1);
    }

    @Test
    void theLeakFiltersNarrowToTheirLines() {
        Shop shop = seededShop();

        assertThat(report(shop, "sale", "&belowUsualPrice=true").totals().salesAmount()).isEqualByComparingTo("150.00");
        assertThat(report(shop, "sale", "&belowCost=true").totals().salesAmount()).isEqualByComparingTo("50.00");
        assertThat(report(shop, "sale", "&cancelled=true").totals().cancelledLineCount()).isEqualTo(1);
    }

    @Test
    void belowTheUsualPriceIsJudgedAgainstThePriceOnTheDayOfTheSale() {
        Shop shop = sellingShop("Usual Price Shop");
        UUID beans = product(shop, "Beans", "200");
        stockIn(shop, beans, 10, "100");
        sell(shop, beans, 1, "200", null);
        sell(shop, beans, 1, "150", null);
        // Cut to 100: the 150 sale above was below the price THEN, and a 150 sale now is not.
        setPrice(shop, beans, "100");
        sell(shop, beans, 1, "150", null);

        assertThat(report(shop, "sale").totals().belowUsualPriceLineCount()).isEqualTo(1);
    }

    @Test
    void aCashierSeesOnlyTheirOwnSalesTally() {
        Shop shop = seededShop();
        Shop cashier = cashier(shop);
        UUID rice = idOf(shop, "Rice");
        sell(cashier, rice, 1, "200", null);
        sell(cashier, rice, 1, "200", null);

        SalesTotals mine = restTemplate.exchange("/api/sales/mine/summary", HttpMethod.GET, new HttpEntity<>(auth(cashier)), SalesTotals.class)
                .getBody();
        assertThat(mine.saleCount()).isEqualTo(2);
        assertThat(mine.salesAmount()).isEqualByComparingTo("400.00");
        // Nothing about cost leaves for the counter, even through the API.
        String raw = restTemplate.exchange("/api/sales/mine/summary", HttpMethod.GET, new HttpEntity<>(auth(cashier)), String.class).getBody();
        assertThat(raw).doesNotContain("costAmount", "grossProfit", "margin", "profitKnownSalesAmount");

        // ...and the full report stays an owner's.
        assertThat(restTemplate.exchange("/api/analytics/sales", HttpMethod.GET, new HttpEntity<>(auth(cashier)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void stockWorthCountsOnlyWhatHasThePriceItNeedsAndSaysHowManyDoNot() {
        Shop shop = seededShop();

        StockWorthResponse worth = restTemplate.exchange("/api/analytics/stock-worth", HttpMethod.GET, new HttpEntity<>(auth(shop)),
                StockWorthResponse.class).getBody();

        // Rice 6 left x 100 + Soap 9 x 60; Oil has no cost. At price: Rice 6 x 200 + Soap 9 x 50.
        assertThat(worth.valueAtCost()).isEqualByComparingTo("1140.00");
        assertThat(worth.valueAtSellingPrice()).isEqualByComparingTo("1650.00");
        assertThat(worth.activeProductCount()).isEqualTo(3);
        assertThat(worth.productsWithoutCost()).isEqualTo(1);
        assertThat(worth.productsWithoutSellingPrice()).isEqualTo(1);
    }

    @Test
    void theDashboardSummaryCarriesTheSalesFigureAndAComparison() {
        Shop shop = seededShop();

        AnalyticsSummaryResponse summary = restTemplate.exchange(
                        "/api/analytics/summary?" + range(), HttpMethod.GET, new HttpEntity<>(auth(shop)), AnalyticsSummaryResponse.class)
                .getBody();

        assertThat(summary.sales().salesAmount()).isEqualByComparingTo("900.00");
        assertThat(summary.previousSales().salesAmount()).isEqualByComparingTo("0");
        // A range under a week compares with the same range a week earlier.
        assertThat(summary.previousFrom()).isNotNull();
    }

    // ------------------------------------------------------------------------- the shop ----

    private record Shop(TenantLoginResponse login, Map<String, UUID> products) {
    }

    private Shop seededShop() {
        Shop shop = sellingShop("Report Shop");
        UUID rice = product(shop, "Rice", "200");
        UUID soap = product(shop, "Soap", "50");
        UUID oil = product(shop, "Oil", null);
        stockIn(shop, rice, 10, "100");
        stockIn(shop, soap, 10, "60");
        stockIn(shop, oil, 10, null);

        UUID s1 = UUID.randomUUID();
        sell(shop, rice, 2, "200", s1);
        sell(shop, soap, 1, "50", s1);
        sell(shop, rice, 1, "150", null);
        sell(shop, oil, 1, "300", null);
        Map<String, Object> unpriced = new HashMap<>(Map.of("quantity", 1, "reason", OutReason.SALE.name()));
        post(shop, "/api/products/" + rice + "/stock/stock-out", unpriced, StockMutationResponse.class);
        UUID s5 = sell(shop, rice, 3, "200", null);
        assertThat(post(shop, "/api/sales/" + s5 + "/cancel", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return shop;
    }

    private Shop sellingShop(String name) {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse login = restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        Shop shop = new Shop(login, new HashMap<>());
        restTemplate.exchange("/api/company/selling", HttpMethod.PUT,
                new HttpEntity<>(new UpdateSellingSettingsRequest(true, null), auth(shop)), SellingSettingsResponse.class);
        return shop;
    }

    private UUID product(Shop shop, String name, String price) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(new CreateProductRequest(
                name, name.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8), null,
                price == null ? null : new BigDecimal(price), null, "PIECE", null, null, null), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        UUID id = restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
        shop.products().put(name, id);
        return id;
    }

    private void setPrice(Shop shop, UUID productId, String price) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(Map.of("unitPrice", new BigDecimal(price)), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertThat(restTemplate.exchange("/api/products/" + productId, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void stockIn(Shop shop, UUID productId, int quantity, String price) {
        Map<String, Object> body = new HashMap<>(Map.of("quantity", quantity));
        if (price != null) body.put("unitPrice", new BigDecimal(price));
        assertThat(post(shop, "/api/products/" + productId + "/stock/stock-in", body, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** @return the sale id the line was recorded under. */
    private UUID sell(Shop shop, UUID productId, int quantity, String price, UUID saleId) {
        Map<String, Object> body = new HashMap<>(Map.of("quantity", quantity, "unitPrice", new BigDecimal(price)));
        if (saleId != null) body.put("saleId", saleId.toString());
        ResponseEntity<StockMutationResponse> response =
                post(shop, "/api/products/" + productId + "/stock/stock-out", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().movement().saleId();
    }

    private Shop cashier(Shop owner) {
        RoleResponse role = restTemplate.exchange("/api/roles", HttpMethod.POST,
                        new HttpEntity<>(new CreateRoleRequest("Cashier " + UUID.randomUUID().toString().substring(0, 8), null,
                                Set.of("STOCK_OUT", "VIEW_PRODUCTS")), auth(owner)), RoleResponse.class)
                .getBody();
        String username = "cashier-" + UUID.randomUUID();
        restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest(username, PASSWORD, role.id(), null, null, null, null, null), auth(owner)),
                String.class);
        return new Shop(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class),
                owner.products());
    }

    // -------------------------------------------------------------------------- helpers ----

    private SalesReportResponse report(Shop shop, String groupBy) {
        return report(shop, groupBy, "");
    }

    private SalesReportResponse report(Shop shop, String groupBy, String extra) {
        ResponseEntity<SalesReportResponse> response = restTemplate.exchange(
                "/api/analytics/sales?groupBy=" + groupBy + "&" + range() + extra,
                HttpMethod.GET, new HttpEntity<>(auth(shop)), SalesReportResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** An hour either side of now - today's sales, without depending on when in the day the test runs. */
    private static String range() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return "from=" + encode(now.minusHours(1)) + "&to=" + encode(now.plusHours(1));
    }

    /** Raw, not URL-encoded: the client encodes the URL itself, and a UTC "...Z" time needs nothing more. */
    private static String encode(OffsetDateTime at) {
        return at.toString();
    }

    private static long sum(List<SalesReportRow> rows, Function<SalesTotals, Long> field) {
        return rows.stream().mapToLong(row -> field.apply(row.totals())).sum();
    }

    private static BigDecimal money(List<SalesReportRow> rows, Function<SalesTotals, BigDecimal> field) {
        return rows.stream().map(row -> field.apply(row.totals())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private UUID idOf(Shop shop, String name) {
        return shop.products().get(name);
    }

    private <T> ResponseEntity<T> post(Shop shop, String path, Object body, Class<T> type) {
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private static HttpHeaders auth(Shop shop) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.login().tokens().accessToken());
        return headers;
    }
}
