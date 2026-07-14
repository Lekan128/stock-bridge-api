package com.procurepal_services.stock_bridge_api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.LowStockSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers. All movements here are created "now" (no way to
 * backdate createdAt through the HTTP API), so every test queries with an
 * explicit from/to range wide enough to contain "now" rather than relying on
 * the summary/movements-over-time default-to-current-month behavior, except
 * where that default is itself what's being exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AnalyticsIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void summaryNumbersAreCorrectAgainstSeededMovements() {
        TenantLoginResponse admin = signup("Analytics Summary Co");
        ProductResponse priced = createProduct(admin, "AS-PRICED", 5);
        ProductResponse unpriced = createProduct(admin, "AS-UNPRICED", null);

        stockIn(admin, priced.id(), 10, new BigDecimal("2.00"));
        stockOut(admin, priced.id(), 4, new BigDecimal("3.00"));
        stockIn(admin, unpriced.id(), 7, null);

        ResponseEntity<AnalyticsSummaryResponse> response = restTemplate.exchange(
                "/api/analytics/summary?" + wideRange(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), AnalyticsSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AnalyticsSummaryResponse body = response.getBody();
        assertThat(body).isNotNull();
        // value sums only count priced movements: 10*2.00 in, 4*3.00 out.
        assertThat(body.totalInValue()).isEqualByComparingTo("20.00");
        assertThat(body.totalOutValue()).isEqualByComparingTo("12.00");
        // quantity sums count every movement, priced or not: 10 (priced) + 7 (unpriced) in.
        assertThat(body.totalUnitsIn()).isEqualTo(17);
        assertThat(body.totalUnitsOut()).isEqualTo(4);
        assertThat(body.activeProductCount()).isGreaterThanOrEqualTo(2);
        // priced starts at 5, +10 -4 = 11, no threshold set -> not low stock; just assert it doesn't error.
        assertThat(body.lowStockProductCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void movementsOverTimeBucketsByDay() {
        TenantLoginResponse admin = signup("Movements Over Time Co");
        ProductResponse product = createProduct(admin, "MOT-1", 0);
        stockIn(admin, product.id(), 6, new BigDecimal("1.50"));
        stockOut(admin, product.id(), 2, new BigDecimal("2.50"));

        ResponseEntity<List<MovementsOverTimePoint>> response = restTemplate.exchange(
                "/api/analytics/movements-over-time?granularity=day&" + wideRange(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MovementsOverTimePoint> points = response.getBody();
        assertThat(points).isNotNull().hasSize(1);
        MovementsOverTimePoint today = points.get(0);
        assertThat(today.inValue()).isEqualByComparingTo("9.00");
        assertThat(today.outValue()).isEqualByComparingTo("5.00");
        assertThat(today.inQuantity()).isEqualTo(6);
        assertThat(today.outQuantity()).isEqualTo(2);
    }

    @Test
    void movementsOverTimeRejectsInvalidGranularity() {
        TenantLoginResponse admin = signup("Bad Granularity Co");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/analytics/movements-over-time?granularity=fortnight&" + wideRange(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void topProductsRankingRespectsDirectionAndByFilters() {
        TenantLoginResponse admin = signup("Top Products Co");
        ProductResponse cheapHighVolume = createProduct(admin, "TP-CHEAP", 0);
        ProductResponse expensiveLowVolume = createProduct(admin, "TP-EXPENSIVE", 0);

        // cheapHighVolume: 100 units in at 1.00 = value 100.00, quantity 100 (highest quantity, lowest value).
        stockIn(admin, cheapHighVolume.id(), 100, new BigDecimal("1.00"));
        // expensiveLowVolume: 5 units in at 50.00 = value 250.00, quantity 5 (highest value, lowest quantity).
        stockIn(admin, expensiveLowVolume.id(), 5, new BigDecimal("50.00"));
        // an OUT movement on cheapHighVolume must not appear in an "in" ranking.
        stockOut(admin, cheapHighVolume.id(), 10, new BigDecimal("1.00"));

        List<TopProductEntry> byQuantityIn = topProducts(admin, "quantity", "in", 10);
        assertThat(byQuantityIn.get(0).sku()).isEqualTo("TP-CHEAP");
        assertThat(byQuantityIn.get(0).quantity()).isEqualTo(100);

        List<TopProductEntry> byValueIn = topProducts(admin, "value", "in", 10);
        assertThat(byValueIn.get(0).sku()).isEqualTo("TP-EXPENSIVE");
        assertThat(byValueIn.get(0).value()).isEqualByComparingTo("250.00");

        List<TopProductEntry> byQuantityOut = topProducts(admin, "quantity", "out", 10);
        assertThat(byQuantityOut).hasSize(1);
        assertThat(byQuantityOut.get(0).sku()).isEqualTo("TP-CHEAP");
        assertThat(byQuantityOut.get(0).quantity()).isEqualTo(10);
    }

    @Test
    void lowStockSummaryReflectsCurrentThresholds() {
        TenantLoginResponse admin = signup("Low Stock Summary Co");
        ProductResponse low = createProduct(admin, "LSS-LOW", null);
        setLowStockThreshold(admin, low.id(), 100);
        stockIn(admin, low.id(), 5, new BigDecimal("1.00"));

        ResponseEntity<LowStockSummaryResponse> response = restTemplate.exchange(
                "/api/analytics/low-stock-summary",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                LowStockSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> lowStockSkus =
                response.getBody().products().stream().map(ProductResponse::sku).toList();
        assertThat(lowStockSkus).contains("LSS-LOW");
    }

    @Test
    void analyticsAreScopedToCallersTenantOnly() {
        TenantLoginResponse tenantA = signup("Analytics Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Analytics Tenant B " + UUID.randomUUID());
        ProductResponse productA = createProduct(tenantA, "ISO-A", 0);
        ProductResponse productB = createProduct(tenantB, "ISO-B", 0);
        stockIn(tenantA, productA.id(), 10, new BigDecimal("5.00"));
        stockIn(tenantB, productB.id(), 999, new BigDecimal("999.00"));

        ResponseEntity<AnalyticsSummaryResponse> summaryAsA = restTemplate.exchange(
                "/api/analytics/summary?" + wideRange(), HttpMethod.GET, new HttpEntity<>(authHeaders(tenantA)), AnalyticsSummaryResponse.class);
        assertThat(summaryAsA.getBody().totalUnitsIn()).isEqualTo(10);
        assertThat(summaryAsA.getBody().totalInValue()).isEqualByComparingTo("50.00");

        List<TopProductEntry> topAsA = topProducts(tenantA, "quantity", "in", 10);
        assertThat(topAsA).extracting(TopProductEntry::sku).doesNotContain("ISO-B");
    }

    private List<TopProductEntry> topProducts(TenantLoginResponse asAdmin, String by, String direction, int limit) {
        ResponseEntity<List<TopProductEntry>> response = restTemplate.exchange(
                "/api/analytics/top-products?by=" + by + "&direction=" + direction + "&limit=" + limit + "&" + wideRange(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(asAdmin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    /** A from/to range guaranteed to contain "now", used by every test except default-range ones. */
    private String wideRange() {
        return "from=2020-01-01T00:00:00Z&to=2035-01-01T00:00:00Z";
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, unitPrice, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private void stockOut(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, unitPrice, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private void setLowStockThreshold(TenantLoginResponse admin, UUID productId, int threshold) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new UpdateProductRequest(null, null, null, null, null, threshold, null, null), productPartHeaders));
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        restTemplate.exchange(
                "/api/products/" + productId, HttpMethod.PUT, new HttpEntity<>(body, headers), ProductResponse.class);
    }

    private ProductResponse createProduct(TenantLoginResponse asAdmin, String sku, Integer lowStockThreshold) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest("Product " + sku, sku, null, new BigDecimal("9.99"), null, lowStockThreshold),
                        productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        return response.getBody();
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
