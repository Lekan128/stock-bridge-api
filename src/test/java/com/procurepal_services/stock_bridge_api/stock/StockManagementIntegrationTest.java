package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * over Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void stockInIncreasesQuantityAndCreatesLedgerRow() {
        TenantLoginResponse admin = signup("Stock In Co");
        ProductResponse product = createProduct(admin, "SI-1");

        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(15, new BigDecimal("2.50"), "initial receipt"), authHeaders(admin)),
                StockMutationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StockMutationResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.product().quantityOnHand()).isEqualTo(15);
        assertThat(body.movement()).isNotNull();
        assertThat(body.movement().movementType()).isEqualTo(MovementType.IN);
        assertThat(body.movement().quantity()).isEqualTo(15);
        assertThat(body.movement().unitPriceAtTime()).isEqualByComparingTo("2.50");

        List<StockMovementResponse> history = history(admin, product.id());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).movementType()).isEqualTo(MovementType.IN);
    }

    @Test
    void stockOutRejectsWhenInsufficientQuantity() {
        TenantLoginResponse admin = signup("Stock Out Co");
        ProductResponse product = createProduct(admin, "SO-1");
        stockIn(admin, product.id(), 5);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(10, null, null), authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<ProductResponse> unchanged = restTemplate.exchange(
                "/api/products/" + product.id(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
        assertThat(unchanged.getBody().quantityOnHand()).isEqualTo(5);
    }

    @Test
    void adjustmentComputesAndRecordsTheDelta() {
        TenantLoginResponse admin = signup("Adjustment Co");
        ProductResponse product = createProduct(admin, "ADJ-1");
        stockIn(admin, product.id(), 20);

        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/adjustment",
                HttpMethod.POST,
                new HttpEntity<>(new StockAdjustmentRequest(13, "stock count correction"), authHeaders(admin)),
                StockMutationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StockMutationResponse body = response.getBody();
        assertThat(body.product().quantityOnHand()).isEqualTo(13);
        assertThat(body.movement().movementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(body.movement().quantity()).isEqualTo(-7);
    }

    @Test
    void concurrentStockOutRequestsDoNotCauseNegativeQuantity() throws InterruptedException {
        TenantLoginResponse admin = signup("Concurrency Co");
        ProductResponse product = createProduct(admin, "CONC-1");
        stockIn(admin, product.id(), 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = List.of(
                    executor.submit(() -> stockOutRaw(admin, product.id(), 7, startLatch)),
                    executor.submit(() -> stockOutRaw(admin, product.id(), 7, startLatch)));
            startLatch.countDown();

            long successCount = 0;
            long rejectedCount = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                HttpStatus status = (HttpStatus) future.get(10, TimeUnit.SECONDS).getStatusCode();
                if (status == HttpStatus.OK) {
                    successCount++;
                } else if (status == HttpStatus.BAD_REQUEST) {
                    rejectedCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(rejectedCount).isEqualTo(1);

            ResponseEntity<ProductResponse> finalState = restTemplate.exchange(
                    "/api/products/" + product.id(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
            assertThat(finalState.getBody().quantityOnHand()).isEqualTo(3);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historyEndpointsAreScopedToCallersTenantOnly() {
        TenantLoginResponse tenantA = signup("History Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("History Tenant B " + UUID.randomUUID());
        ProductResponse productA = createProduct(tenantA, "HIST-A");
        ProductResponse productB = createProduct(tenantB, "HIST-B");
        stockIn(tenantA, productA.id(), 5);
        stockIn(tenantB, productB.id(), 5);

        ResponseEntity<ApiError> crossTenantHistory = restTemplate.exchange(
                "/api/products/" + productB.id() + "/stock/history",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantA)),
                ApiError.class);
        assertThat(crossTenantHistory.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<TestPage<StockMovementResponse>> allMovementsAsA = restTemplate.exchange(
                "/api/stock/movements",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantA)),
                new ParameterizedTypeReference<>() {});
        List<UUID> productIdsVisibleToA = allMovementsAsA.getBody().content().stream()
                .map(StockMovementResponse::productId)
                .toList();
        assertThat(productIdsVisibleToA).contains(productA.id()).doesNotContain(productB.id());
    }

    private ResponseEntity<String> stockOutRaw(TenantLoginResponse admin, UUID productId, int quantity, CountDownLatch startLatch) {
        try {
            startLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, null, null), authHeaders(admin)),
                String.class);
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, null, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private List<StockMovementResponse> history(TenantLoginResponse admin, UUID productId) {
        ResponseEntity<TestPage<StockMovementResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/history",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().content();
    }

    private ProductResponse createProduct(TenantLoginResponse asAdmin, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest("Product " + sku, sku, null, new BigDecimal("9.99"), null, null, null),
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

    private record TestPage<T>(List<T> content) {
    }
}
