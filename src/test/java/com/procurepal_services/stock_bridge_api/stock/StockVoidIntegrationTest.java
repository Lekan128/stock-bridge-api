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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Undo (B2, decision D8): a stock write voided moments after it was made comes off as if it had
 * never been made - the figure, the cost price, the supplier's balances and pack, the lots - or is
 * refused with a sentence saying why and what to do instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockVoidIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aStockInIsVoidedWithItsFigureAndCostPriceRestored() {
        Session s = session("Void In");
        stockIn(s, Map.of("quantity", 20, "unitPrice", 10));
        StockMutationResponse second = stockIn(s, Map.of("quantity", 10, "unitPrice", 16));
        assertThat(second.product().costPrice()).isEqualByComparingTo("12.00");

        ResponseEntity<StockMutationResponse> voided = voidWrite(s, second.movement().id(), StockMutationResponse.class);

        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().product().quantityOnHand()).isEqualTo(20);
        assertThat(voided.getBody().product().costPrice()).isEqualByComparingTo("10.00");
        assertThat(history(s)).extracting(StockMovementResponse::id).doesNotContain(second.movement().id());
    }

    /** The lots a voided sale drew from are whole again: all of them can be sold. */
    @Test
    void aStockOutIsVoidedAndTheLotsItDrewFromComeBack() {
        Session s = session("Void Out");
        stockIn(s, Map.of("quantity", 20));
        StockMutationResponse sale = post(s, "stock-out", Map.of("quantity", 5), StockMutationResponse.class).getBody();

        ResponseEntity<StockMutationResponse> voided = voidWrite(s, sale.movement().id(), StockMutationResponse.class);

        assertThat(voided.getBody().product().quantityOnHand()).isEqualTo(20);
        assertThat(post(s, "stock-out", Map.of("quantity", 20), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCountIsVoided() {
        Session s = session("Void Count");
        stockIn(s, Map.of("quantity", 20));
        StockMutationResponse counted = post(s, "count", Map.of("countedQuantity", 13), StockMutationResponse.class).getBody();

        ResponseEntity<StockMutationResponse> voided = voidWrite(s, counted.movement().id(), StockMutationResponse.class);

        assertThat(voided.getBody().product().quantityOnHand()).isEqualTo(20);
        assertThat(history(s)).noneMatch(m -> m.movementType() == MovementType.ADJUSTMENT);
    }

    /** A supplier delivery: the supplier's balances and its pack's last price come back too. */
    @Test
    void aSupplierDeliveryIsVoidedWithTheSuppliersFiguresRestored() {
        Session s = session("Void Supplier");
        UUID supplier = supplier(s, "Ada Millers");
        stockIn(s, Map.of("quantity", 20, "unitPrice", 10, "companyVendorId", supplier));
        StockMutationResponse second = stockIn(s, Map.of("quantity", 10, "unitPrice", 16, "companyVendorId", supplier));
        assertThat(supplierLine(s).get("lastCostPrice").toString()).startsWith("16");

        assertThat(voidWrite(s, second.movement().id(), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> line = supplierLine(s);
        assertThat(line.get("quantityOnHandFromVendor")).isEqualTo(20);
        assertThat(line.get("totalQuantityReceived")).isEqualTo(20);
        assertThat(new BigDecimal(line.get("lastCostPrice").toString())).isEqualByComparingTo("10");
    }

    /** Undoing the first delivery from a supplier would have to remove the supplier too. */
    @Test
    void theFirstDeliveryFromANewSupplierCannotBeVoided() {
        Session s = session("Void New Supplier");
        UUID supplier = supplier(s, "Tunde Foods");
        StockMutationResponse first = stockIn(s, Map.of("quantity", 20, "companyVendorId", supplier));

        ResponseEntity<String> refused = voidWrite(s, first.movement().id(), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("first delivery from this supplier");
        assertThat(product(s).quantityOnHand()).isEqualTo(20);
    }

    @Test
    void onlyTheProductsLatestWriteCanBeVoided() {
        Session s = session("Void Latest");
        StockMutationResponse delivery = stockIn(s, Map.of("quantity", 20));
        post(s, "stock-out", Map.of("quantity", 5), StockMutationResponse.class);

        ResponseEntity<String> refused = voidWrite(s, delivery.movement().id(), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("Something else has been recorded for this product since");
        assertThat(product(s).quantityOnHand()).isEqualTo(15);
    }

    @Test
    void afterTwoMinutesItIsTooLate() {
        Session s = session("Void Late");
        StockMutationResponse delivery = stockIn(s, Map.of("quantity", 20));
        jdbc.update(
                "UPDATE stock_write_undo SET created_at = now() - interval '3 minutes' WHERE movement_id = ?",
                delivery.movement().id());

        ResponseEntity<String> refused = voidWrite(s, delivery.movement().id(), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("too late");
    }

    @Test
    void onlyThePersonWhoRecordedItCanVoidIt() {
        Session s = session("Void Someone Else");
        StockMutationResponse delivery = stockIn(s, Map.of("quantity", 20));
        jdbc.update("UPDATE stock_write_undo SET created_by = ? WHERE movement_id = ?", UUID.randomUUID(), delivery.movement().id());

        ResponseEntity<String> refused = voidWrite(s, delivery.movement().id(), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("Only the person who recorded this");
    }

    /** No snapshot to restore from - a bulk import, a late write from a phone, a write from before V39. */
    @Test
    void aWriteWithNothingToRestoreFromCannotBeVoided() {
        Session s = session("Void No Snapshot");
        stockIn(s, Map.of("quantity", 20));
        StockMutationResponse second = stockIn(s, Map.of("quantity", 5));
        jdbc.update("DELETE FROM stock_write_undo WHERE movement_id = ?", second.movement().id());

        ResponseEntity<String> refused = voidWrite(s, second.movement().id(), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("can't be undone");
        assertThat(product(s).quantityOnHand()).isEqualTo(25);
    }

    @Test
    void aVoidedWriteCannotBeVoidedTwice() {
        Session s = session("Void Twice");
        stockIn(s, Map.of("quantity", 20));
        StockMutationResponse second = stockIn(s, Map.of("quantity", 5));
        voidWrite(s, second.movement().id(), String.class);

        ResponseEntity<String> again = voidWrite(s, second.movement().id(), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(product(s).quantityOnHand()).isEqualTo(20);
    }

    // ------------------------------------------------------------------------------- helpers

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    private record TestPage<T>(List<T> content) {
    }

    private StockMutationResponse stockIn(Session s, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = post(s, "stock-in", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> voidWrite(Session s, UUID movementId, Class<T> type) {
        return post(s, "movements/" + movementId + "/void", Map.of(), type);
    }

    private <T> ResponseEntity<T> post(Session s, String action, Object body, Class<T> type) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/products/" + s.productId() + "/stock/" + action, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private UUID supplier(Session s, String name) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("name", name + " " + UUID.randomUUID().toString().substring(0, 6));
        body.put("contactPhone", "0803 000 0000");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/company-vendors", HttpMethod.POST, new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private Map<String, Object> supplierLine(Session s) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/products/" + s.productId() + "/vendors",
                HttpMethod.GET,
                new HttpEntity<>(auth(s)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().getFirst();
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
                new CreateProductRequest("Product " + unique.substring(0, 6), "V-" + unique.substring(0, 8), null,
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
}
