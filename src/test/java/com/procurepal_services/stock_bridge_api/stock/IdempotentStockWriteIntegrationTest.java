package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * The {@code Idempotency-Key} contract on the three stock writes - see
 * {@link StockIdempotencyService}. Real HTTP against the local docker-compose Postgres, like
 * {@link StockManagementIntegrationTest}, because the duplicate-in-flight case depends on
 * Postgres's own unique-index wait and cannot be faked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class IdempotentStockWriteIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    /** The bug this closes: response lost, user taps Confirm again, delivery recorded twice. */
    @Test
    void retryingAStockInWithTheSameKeyRecordsItOnce() {
        TenantLoginResponse admin = signup("Idem Retry Co");
        ProductResponse product = createProduct(admin, "IDEM-1");
        String key = UUID.randomUUID().toString();
        StockInRequest request = new StockInRequest(15, new BigDecimal("2.50"), "delivery");

        ResponseEntity<StockMutationResponse> first = post(admin, product.id(), "stock-in", request, key);
        ResponseEntity<StockMutationResponse> second = post(admin, product.id(), "stock-in", request, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isNull();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("true");
        // The replay is the first answer, not a second write that happens to look the same.
        assertThat(second.getBody().movement().id()).isEqualTo(first.getBody().movement().id());
        assertThat(second.getBody().product().quantityOnHand()).isEqualTo(15);

        assertThat(quantityOnHand(admin, product.id())).isEqualTo(15);
        assertThat(history(admin, product.id())).hasSize(1);
    }

    @Test
    void stockOutAndAdjustmentAreIdempotentToo() {
        TenantLoginResponse admin = signup("Idem Out Co");
        ProductResponse product = createProduct(admin, "IDEM-2");
        post(admin, product.id(), "stock-in", new StockInRequest(20, null, null), null);

        String outKey = UUID.randomUUID().toString();
        StockOutRequest out = new StockOutRequest(5, null, "sold");
        post(admin, product.id(), "stock-out", out, outKey);
        post(admin, product.id(), "stock-out", out, outKey);
        assertThat(quantityOnHand(admin, product.id())).isEqualTo(15);

        String adjustKey = UUID.randomUUID().toString();
        StockAdjustmentRequest adjust = new StockAdjustmentRequest(12, "count");
        post(admin, product.id(), "adjustment", adjust, adjustKey);
        post(admin, product.id(), "adjustment", adjust, adjustKey);
        assertThat(quantityOnHand(admin, product.id())).isEqualTo(12);

        // in + out + adjustment, each exactly once.
        assertThat(history(admin, product.id())).hasSize(3);
    }

    /** Two copies racing - a double tap, or a retry fired while the first is still in flight. */
    @Test
    void twoConcurrentRequestsWithTheSameKeyRecordOnce() throws Exception {
        TenantLoginResponse admin = signup("Idem Race Co");
        ProductResponse product = createProduct(admin, "IDEM-3");
        String key = UUID.randomUUID().toString();
        StockInRequest request = new StockInRequest(7, null, null);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<StockMutationResponse>>> futures = List.of(
                    executor.submit(() -> awaitThenPost(start, admin, product.id(), request, key)),
                    executor.submit(() -> awaitThenPost(start, admin, product.id(), request, key)),
                    executor.submit(() -> awaitThenPost(start, admin, product.id(), request, key)),
                    executor.submit(() -> awaitThenPost(start, admin, product.id(), request, key)));
            start.countDown();
            for (Future<ResponseEntity<StockMutationResponse>> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(quantityOnHand(admin, product.id())).isEqualTo(7);
        assertThat(history(admin, product.id())).hasSize(1);
    }

    /** A key names one intended write; a different body under it is a client bug, not a replay. */
    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        TenantLoginResponse admin = signup("Idem Reuse Co");
        ProductResponse product = createProduct(admin, "IDEM-4");
        String key = UUID.randomUUID().toString();

        post(admin, product.id(), "stock-in", new StockInRequest(10, null, null), key);
        ResponseEntity<String> reused = postRaw(admin, product.id(), "stock-in", new StockInRequest(99, null, null), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quantityOnHand(admin, product.id())).isEqualTo(10);
    }

    /**
     * The phone's outbox stamps when the write happened on a resend, which the first try never
     * carried. That is the same write, not a reused key (found by the Phase H chaos run: a lost
     * reply turned into "needs you" for a delivery the server had recorded).
     */
    @Test
    void aResendCarryingWhenItHappenedIsStillAReplay() {
        TenantLoginResponse admin = signup("Idem Timing Co");
        ProductResponse product = createProduct(admin, "IDEM-8");
        String key = UUID.randomUUID().toString();

        post(admin, product.id(), "stock-in", Map.of("quantity", 9), key);
        ResponseEntity<StockMutationResponse> resend = post(
                admin,
                product.id(),
                "stock-in",
                Map.of("quantity", 9, "occurredAt", OffsetDateTime.now().minusMinutes(3).toString(), "recordedOffline", true),
                key);

        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resend.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("true");
        assertThat(quantityOnHand(admin, product.id())).isEqualTo(9);
        assertThat(history(admin, product.id())).hasSize(1);
    }

    /** A failed write leaves no key behind, so the same intended write can succeed later. */
    @Test
    void aFailedRequestDoesNotBurnItsKey() {
        TenantLoginResponse admin = signup("Idem Fail Co");
        ProductResponse product = createProduct(admin, "IDEM-5");
        String key = UUID.randomUUID().toString();
        StockOutRequest out = new StockOutRequest(5, null, null);

        ResponseEntity<String> refused = postRaw(admin, product.id(), "stock-out", out, key);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        post(admin, product.id(), "stock-in", new StockInRequest(8, null, null), null);
        ResponseEntity<StockMutationResponse> retried = post(admin, product.id(), "stock-out", out, key);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isNull();
        assertThat(quantityOnHand(admin, product.id())).isEqualTo(3);
    }

    /** Keys are per company: two tenants' clients generating the same key never collide. */
    @Test
    void theSameKeyInTwoCompaniesIsTwoWrites() {
        TenantLoginResponse tenantA = signup("Idem Tenant A");
        TenantLoginResponse tenantB = signup("Idem Tenant B");
        ProductResponse productA = createProduct(tenantA, "IDEM-A");
        ProductResponse productB = createProduct(tenantB, "IDEM-B");
        String key = UUID.randomUUID().toString();

        post(tenantA, productA.id(), "stock-in", new StockInRequest(4, null, null), key);
        ResponseEntity<StockMutationResponse> b = post(tenantB, productB.id(), "stock-in", new StockInRequest(4, null, null), key);

        assertThat(b.getHeaders().getFirst(StockController.IDEMPOTENT_REPLAYED_HEADER)).isNull();
        assertThat(quantityOnHand(tenantA, productA.id())).isEqualTo(4);
        assertThat(quantityOnHand(tenantB, productB.id())).isEqualTo(4);
    }

    @Test
    void withoutAKeyBehaviourIsUnchanged() {
        TenantLoginResponse admin = signup("Idem None Co");
        ProductResponse product = createProduct(admin, "IDEM-6");
        StockInRequest request = new StockInRequest(3, null, null);

        post(admin, product.id(), "stock-in", request, null);
        post(admin, product.id(), "stock-in", request, null);

        assertThat(quantityOnHand(admin, product.id())).isEqualTo(6);
    }

    @Test
    void anOverlongKeyIsRefused() {
        TenantLoginResponse admin = signup("Idem Long Co");
        ProductResponse product = createProduct(admin, "IDEM-7");

        ResponseEntity<String> response =
                postRaw(admin, product.id(), "stock-in", new StockInRequest(1, null, null), "k".repeat(101));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(quantityOnHand(admin, product.id())).isZero();
    }

    private ResponseEntity<StockMutationResponse> awaitThenPost(
            CountDownLatch start, TenantLoginResponse admin, UUID productId, Object body, String key)
            throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        return post(admin, productId, "stock-in", body, key);
    }

    private ResponseEntity<StockMutationResponse> post(
            TenantLoginResponse admin, UUID productId, String action, Object body, String key) {
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/" + action,
                HttpMethod.POST,
                new HttpEntity<>(body, headers(admin, key)),
                StockMutationResponse.class);
    }

    private ResponseEntity<String> postRaw(TenantLoginResponse admin, UUID productId, String action, Object body, String key) {
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/" + action,
                HttpMethod.POST,
                new HttpEntity<>(body, headers(admin, key)),
                String.class);
    }

    private HttpHeaders headers(TenantLoginResponse admin, String key) {
        HttpHeaders headers = authHeaders(admin);
        if (key != null) {
            headers.set(StockController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return headers;
    }

    private int quantityOnHand(TenantLoginResponse admin, UUID productId) {
        return restTemplate
                .exchange("/api/products/" + productId, HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class)
                .getBody()
                .quantityOnHand();
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
