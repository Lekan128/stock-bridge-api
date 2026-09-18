package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementSummaryResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
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
 * The stock in/out report - {@code GET /api/stock/movements} and its {@code /summary} sibling.
 *
 * <p>Same local-Postgres harness and same self-contained-helpers convention as
 * {@link StockManagementIntegrationTest}; see that class for why local Postgres over
 * Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockMovementReportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * The report rows carry the product identity they need to be readable across products, and a
     * line value that is the product of the two columns beside it - the fields V28 added for
     * exactly this screen. Also the regression guard on the fetch join: if it were dropped these
     * would still populate, one query per row, so this asserts the contract rather than the plan.
     */
    @Test
    void reportRowsNameTheirProductAndCarryALineValue() {
        TenantLoginResponse admin = signup("Report Co");
        ProductResponse product = createProduct(admin, "RPT-1");
        stockIn(admin, product.id(), 10, new BigDecimal("250.00"));

        List<StockMovementResponse> rows = movements(admin, "");

        assertThat(rows).hasSize(1);
        StockMovementResponse row = rows.getFirst();
        assertThat(row.productId()).isEqualTo(product.id());
        assertThat(row.productName()).isEqualTo("Product RPT-1");
        assertThat(row.productSku()).isEqualTo("RPT-1");
        assertThat(row.quantity()).isEqualTo(10);
        assertThat(row.unitPriceAtTime()).isEqualByComparingTo("250.00");
        assertThat(row.lineValue()).isEqualByComparingTo("2500.00");
        assertThat(row.occurredAt()).isNotNull();
    }

    /**
     * A movement with no price recorded reports a null {@code lineValue}, not a zero. The
     * distinction is the whole reason the field is nullable - "we do not know what this was worth"
     * and "this was worth nothing" are different claims, and a screen that rendered the second for
     * the first would be stating something false about money.
     */
    @Test
    void anUnpricedMovementHasNoLineValueRatherThanAZeroOne() {
        TenantLoginResponse admin = signup("Unpriced Co");
        ProductResponse product = createProduct(admin, "RPT-2");
        stockIn(admin, product.id(), 4, null);

        StockMovementResponse row = movements(admin, "").getFirst();

        assertThat(row.unitPriceAtTime()).isNull();
        assertThat(row.lineValue()).isNull();
    }

    /**
     * The summary aggregates the whole filtered set, separates the two directions, and counts
     * rather than values what it cannot value.
     *
     * <p>This is also the only place the {@code CAST(:param AS uuid) IS NULL} form in {@code
     * StockMovementRepository.summarise} is exercised with every optional filter absent - the
     * shape that fails to prepare at all if the casts are ever removed, since a null bind leaves
     * Postgres with no type to infer.
     */
    @Test
    void summaryTotalsEachDirectionAndCountsWhatItCannotValue() {
        TenantLoginResponse admin = signup("Summary Co");
        ProductResponse product = createProduct(admin, "RPT-3");

        stockIn(admin, product.id(), 10, new BigDecimal("100.00")); // 1,000 in
        stockIn(admin, product.id(), 5, new BigDecimal("200.00")); //  1,000 in
        stockIn(admin, product.id(), 3, null); //     unpriced
        stockOut(admin, product.id(), 4, new BigDecimal("300.00")); // 1,200 out
        adjust(admin, product.id(), 12); //     an adjustment

        StockMovementSummaryResponse summary = summary(admin, "");

        assertThat(summary.inValue()).isEqualByComparingTo("2000.00");
        assertThat(summary.outValue()).isEqualByComparingTo("1200.00");
        // Every IN in range counts toward quantity whether or not it carried a price, which is
        // exactly why the unpriced count below has to be published beside it.
        assertThat(summary.inQuantity()).isEqualTo(18);
        assertThat(summary.outQuantity()).isEqualTo(4);
        assertThat(summary.inMovementCount()).isEqualTo(3);
        assertThat(summary.outMovementCount()).isEqualTo(1);
        assertThat(summary.unpricedInCount()).isEqualTo(1);
        assertThat(summary.unpricedOutCount()).isZero();
        // Counted, and deliberately absent from both value totals: a stock-take correction is
        // neither a purchase nor a sale.
        assertThat(summary.adjustmentCount()).isEqualTo(1);
    }

    /** The movementType filter narrows the rows and the totals identically - or the footer lies. */
    @Test
    void theMovementTypeFilterNarrowsRowsAndTotalsAlike() {
        TenantLoginResponse admin = signup("Filtered Co");
        ProductResponse product = createProduct(admin, "RPT-4");
        stockIn(admin, product.id(), 10, new BigDecimal("100.00"));
        stockOut(admin, product.id(), 2, new BigDecimal("150.00"));

        List<StockMovementResponse> inRows = movements(admin, "?movementType=IN");
        StockMovementSummaryResponse inSummary = summary(admin, "?movementType=IN");

        assertThat(inRows).singleElement().extracting(StockMovementResponse::movementType).isEqualTo(MovementType.IN);
        assertThat(inSummary.inValue()).isEqualByComparingTo("1000.00");
        assertThat(inSummary.outValue()).isEqualByComparingTo("0");
        assertThat(inSummary.outMovementCount()).isZero();
    }

    /**
     * The date range brackets {@code occurredAt}, not {@code createdAt}. Asserted through a
     * backdated delivery, which is the only case that can tell the two apart and the case bulk
     * stock-in makes ordinary: the row is written now but happened a year ago, so a window ending
     * before today must still contain it.
     */
    @Test
    void theDateRangeFiltersOnWhenTheDeliveryHappenedNotWhenItWasEntered() {
        TenantLoginResponse admin = signup("Backdated Co");
        ProductResponse product = createProduct(admin, "RPT-5");
        stockInOccurringAt(admin, product.id(), 7, new BigDecimal("50.00"), "2020-03-15T09:00:00Z");

        // A window around the delivery date, closing long before the row was written.
        String backThen = "?from=2020-03-01T00:00:00Z&to=2020-03-31T23:59:59Z";
        assertThat(movements(admin, backThen)).hasSize(1);
        assertThat(summary(admin, backThen).inValue()).isEqualByComparingTo("350.00");

        // A window that contains the moment it was ENTERED but not the moment it HAPPENED. Under
        // a createdAt filter this would return the row; under occurredAt it must not.
        String recently = "?from=2026-01-01T00:00:00Z&to=2030-01-01T00:00:00Z";
        assertThat(movements(admin, recently)).isEmpty();
        assertThat(summary(admin, recently).inValue()).isEqualByComparingTo("0");
    }

    /** One tenant's report never contains another's ledger, filters or no filters. */
    @Test
    void theReportIsScopedToTheCallersTenant() {
        TenantLoginResponse tenantA = signup("Report Tenant A");
        TenantLoginResponse tenantB = signup("Report Tenant B");
        ProductResponse productA = createProduct(tenantA, "RPT-A");
        ProductResponse productB = createProduct(tenantB, "RPT-B");
        stockIn(tenantA, productA.id(), 5, new BigDecimal("10.00"));
        stockIn(tenantB, productB.id(), 5, new BigDecimal("99.00"));

        assertThat(movements(tenantA, "")).extracting(StockMovementResponse::productId).containsExactly(productA.id());
        assertThat(summary(tenantA, "").inValue()).isEqualByComparingTo("50.00");
    }

    // ------------------------------------------------------------------------------- helpers

    private List<StockMovementResponse> movements(TenantLoginResponse admin, String query) {
        ResponseEntity<TestPage<StockMovementResponse>> response = restTemplate.exchange(
                "/api/stock/movements" + query,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().content();
    }

    private StockMovementSummaryResponse summary(TenantLoginResponse admin, String query) {
        ResponseEntity<StockMovementSummaryResponse> response = restTemplate.exchange(
                "/api/stock/movements/summary" + query,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                StockMovementSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, unitPrice, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    /**
     * Posts the raw JSON rather than the request record, so the test does not have to track every
     * component {@code StockInRequest} has picked up since - it needs exactly three of them.
     */
    private void stockInOccurringAt(
            TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice, String occurredAt) {
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"quantity\":%d,\"unitPrice\":%s,\"occurredAt\":\"%s\"}".formatted(quantity, unitPrice, occurredAt);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void stockOut(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, unitPrice, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private void adjust(TenantLoginResponse admin, UUID productId, int newQuantity) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/adjustment",
                HttpMethod.POST,
                new HttpEntity<>(new StockAdjustmentRequest(newQuantity, "stock count"), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private ProductResponse createProduct(TenantLoginResponse asAdmin, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest(
                                "Product " + sku, sku, null, new BigDecimal("9.99"), null, null, null, null, null),
                        productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin);
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

    private record TestPage<T>(List<T> content) {
    }
}
