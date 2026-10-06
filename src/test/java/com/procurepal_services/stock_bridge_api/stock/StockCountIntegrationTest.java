package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Stock counts and late writes from offline phones (A4, decision D1).
 *
 * <p>A count says what was on the shelf at a moment. Writes recorded as happening after it are
 * carried forward on top; an older count is superseded by a newer one; and a sale or delivery from
 * BEFORE a count, arriving late from a phone that was offline, is already in the count and must not
 * move the shelf figure a second time. Writes not flagged as late keep their existing behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockCountIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void anOnlineCountSetsTheShelfFigureLikeTheOldAdjust() {
        Session s = session("Count Online");
        stockIn(s, 20, hoursAgo(3), false);

        StockMutationResponse counted = count(s, 13, null);

        assertThat(counted.product().quantityOnHand()).isEqualTo(13);
        assertThat(counted.movement().movementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(counted.movement().quantity()).isEqualTo(-7);
    }

    /** It still counts as a look at the shelf, so it is kept - later writes are judged against it. */
    @Test
    void aCountThatMatchesTheBooksIsStillRecorded() {
        Session s = session("Count Match");
        stockIn(s, 20, hoursAgo(3), false);

        StockMutationResponse counted = count(s, 20, null);

        assertThat(counted.product().quantityOnHand()).isEqualTo(20);
        assertThat(counted.movement()).isNotNull();
        assertThat(counted.movement().quantity()).isZero();
    }

    /** The heart of D1: a count from an offline phone never erases another phone's later sale. */
    @Test
    void aLateCountIsCarriedForwardBySalesRecordedAfterIt() {
        Session s = session("Count Late");
        stockIn(s, 20, hoursAgo(3), false);
        stockOut(s, 5, null, false); // another phone sells now

        StockMutationResponse counted = count(s, 15, hoursAgo(1)); // counted an hour ago, arriving now

        assertThat(counted.product().quantityOnHand()).isEqualTo(10);
    }

    @Test
    void anOlderCountIsSupersededByANewerOne() {
        Session s = session("Count Supersede");
        stockIn(s, 20, hoursAgo(3), false);
        count(s, 12, minutesAgo(10));

        StockMutationResponse older = count(s, 30, hoursAgo(1));

        assertThat(older.movement()).as("superseded: nothing recorded").isNull();
        assertThat(older.product().quantityOnHand()).isEqualTo(12);
    }

    @Test
    void salesAfterACountCanNeverPushTheShelfBelowZero() {
        Session s = session("Count Clamp");
        stockIn(s, 20, hoursAgo(3), false);
        stockOut(s, 8, null, false);

        StockMutationResponse counted = count(s, 2, hoursAgo(1));

        assertThat(counted.product().quantityOnHand()).isZero();
    }

    /** The mirror case: a sale made offline BEFORE a count, arriving after it. */
    @Test
    void aLateOfflineSaleFromBeforeACountDoesNotMoveTheCountedFigure() {
        Session s = session("Count Absorb Out");
        stockIn(s, 20, hoursAgo(3), false);
        count(s, 15, hoursAgo(1)); // the shelf already showed the sale below

        StockMutationResponse sale = stockOut(s, 5, hoursAgo(2), true);

        assertThat(sale.product().quantityOnHand()).isEqualTo(15);
        List<StockMovementResponse> history = history(s);
        assertThat(history).anySatisfy(m -> {
            assertThat(m.movementType()).isEqualTo(MovementType.OUT);
            assertThat(m.quantity()).isEqualTo(5);
        });
        assertThat(history).anySatisfy(m -> {
            assertThat(m.movementType()).isEqualTo(MovementType.ADJUSTMENT);
            assertThat(m.quantity()).isEqualTo(5);
        });
    }

    /** The count said the shelf was empty; an earlier sale must not be refused as an oversell. */
    @Test
    void aLateOfflineSaleIsNotRefusedByTheCeilingTheCountAlreadyAccountsFor() {
        Session s = session("Count Absorb Ceiling");
        stockIn(s, 5, hoursAgo(3), false);
        count(s, 0, hoursAgo(1));

        ResponseEntity<String> sale = stockOutRaw(s, 5, hoursAgo(2), true);

        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(product(s).quantityOnHand()).isZero();
    }

    @Test
    void aLateOfflineDeliveryFromBeforeACountDoesNotMoveTheCountedFigure() {
        Session s = session("Count Absorb In");
        stockIn(s, 20, hoursAgo(3), false);
        count(s, 30, hoursAgo(1)); // the 10 below were already on the shelf

        StockMutationResponse delivery = stockIn(s, 10, hoursAgo(2), true);

        assertThat(delivery.product().quantityOnHand()).isEqualTo(30);
    }

    /** Only late writes from the outbox are judged against counts; a backdated online write is not. */
    @Test
    void aBackdatedWriteThatIsNotFlaggedOfflineBehavesExactlyAsBefore() {
        Session s = session("Count Not Offline");
        stockIn(s, 20, hoursAgo(3), false);
        count(s, 15, hoursAgo(1));

        StockMutationResponse sale = stockOut(s, 5, hoursAgo(2), false);

        assertThat(sale.product().quantityOnHand()).isEqualTo(10);
    }

    @Test
    void anOfflineSaleFromAfterTheCountIsAppliedNormally() {
        Session s = session("Count After");
        stockIn(s, 20, hoursAgo(3), false);
        count(s, 15, hoursAgo(2));

        StockMutationResponse sale = stockOut(s, 5, hoursAgo(1), true);

        assertThat(sale.product().quantityOnHand()).isEqualTo(10);
    }

    @Test
    void aStockOutRecordsWhenTheSaleHappened() {
        Session s = session("Out OccurredAt");
        stockIn(s, 20, hoursAgo(3), false);
        OffsetDateTime when = hoursAgo(2);

        StockMutationResponse sale = stockOut(s, 5, when, true);

        assertThat(sale.movement().occurredAt()).isCloseTo(when, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void aCountSentTwiceWithTheSameKeyIsRecordedOnce() {
        Session s = session("Count Idem");
        stockIn(s, 20, hoursAgo(3), false);
        String key = UUID.randomUUID().toString();

        post(s, "count", Map.of("countedQuantity", 13), key, StockMutationResponse.class);
        post(s, "count", Map.of("countedQuantity", 13), key, StockMutationResponse.class);

        long counts = history(s).stream().filter(m -> m.movementType() == MovementType.ADJUSTMENT).count();
        assertThat(counts).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- helpers

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    private static OffsetDateTime hoursAgo(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private StockMutationResponse count(Session s, int quantity, OffsetDateTime countedAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("countedQuantity", quantity);
        if (countedAt != null) body.put("countedAt", countedAt.toString());
        return post(s, "count", body, null, StockMutationResponse.class).getBody();
    }

    private StockMutationResponse stockIn(Session s, int quantity, OffsetDateTime occurredAt, boolean recordedOffline) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("occurredAt", occurredAt.toString());
        body.put("recordedOffline", recordedOffline);
        ResponseEntity<StockMutationResponse> response = post(s, "stock-in", body, null, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private StockMutationResponse stockOut(Session s, int quantity, OffsetDateTime occurredAt, boolean recordedOffline) {
        ResponseEntity<StockMutationResponse> response =
                post(s, "stock-out", outBody(quantity, occurredAt, recordedOffline), null, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> stockOutRaw(Session s, int quantity, OffsetDateTime occurredAt, boolean recordedOffline) {
        return post(s, "stock-out", outBody(quantity, occurredAt, recordedOffline), null, String.class);
    }

    private static Map<String, Object> outBody(int quantity, OffsetDateTime occurredAt, boolean recordedOffline) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        if (occurredAt != null) body.put("occurredAt", occurredAt.toString());
        body.put("recordedOffline", recordedOffline);
        return body;
    }

    private <T> ResponseEntity<T> post(Session s, String action, Object body, String key, Class<T> type) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return restTemplate.exchange(
                "/api/products/" + s.productId() + "/stock/" + action, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ProductResponse product(Session s) {
        return restTemplate
                .exchange("/api/products/" + s.productId(), HttpMethod.GET, new HttpEntity<>(auth(s)), ProductResponse.class)
                .getBody();
    }

    private List<StockMovementResponse> history(Session s) {
        ResponseEntity<TestPage<StockMovementResponse>> response = restTemplate.exchange(
                "/api/products/" + s.productId() + "/stock/history?size=50",
                HttpMethod.GET,
                new HttpEntity<>(auth(s)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().content();
    }

    private Session session(String name) {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse login = restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest("Product " + unique.substring(0, 6), "C-" + unique.substring(0, 8), null,
                        new BigDecimal("9.99"), null, null, null, null, null),
                part));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ProductResponse product = restTemplate
                .exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody();
        return new Session(login, product.id());
    }

    private HttpHeaders auth(Session s) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(s.login().tokens().accessToken());
        return headers;
    }

    private record TestPage<T>(List<T> content) {
    }
}
