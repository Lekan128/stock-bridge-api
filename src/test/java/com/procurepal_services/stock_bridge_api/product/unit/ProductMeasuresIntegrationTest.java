package com.procurepal_services.stock_bridge_api.product.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
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
 * A product's own units (UNITS_PLAN.md Parts 1-3): rice bought in 80 kg bags and used in dericas and cups. A measure
 * joins the product's unit set, an entry in it records the stock units it holds and keeps what was typed, a re-weighed
 * measure counts from then on only, and one that has been used is hidden rather than removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductMeasuresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void riceInBagsIsUsedInDericasAndEveryEntryKeepsWhatWasTyped() {
        TenantLoginResponse owner = signup("Derica Kitchen");
        ProductResponse rice = create(owner, "Rice", "KG", "BAG", new BigDecimal("80"),
                List.of(new ProductMeasure("derica", new BigDecimal("0.6")), new ProductMeasure("Cup", new BigDecimal("0.2"))),
                "Derica");

        // Named as the kitchen spells it, first letter up; offered beside the stock unit and the bag.
        assertThat(rice.measures()).extracting(ProductMeasure::name).containsExactly("Derica", "Cup");
        assertThat(rice.usedInUnit()).isEqualTo("Derica");
        UnitOption derica = rice.unitOptions().stream().filter(option -> option.code().equals("Derica")).findFirst().orElseThrow();
        assertThat(derica.isMeasure()).isTrue();
        assertThat(derica.factorToStockUnit()).isEqualByComparingTo("0.6");

        stockIn(owner, rice.id(), new BigDecimal("2"), "BAG");
        StockMutationResponse used = stockOut(owner, rice.id(), new BigDecimal("5"), "Derica");
        // 5 dericas of 0.6 kg: exactly 3 kg, never rounded to a whole kg.
        assertThat(used.movement().quantity()).isEqualByComparingTo("3");
        assertThat(used.product().quantityOnHand()).isEqualByComparingTo("157");
        StockMutationResponse sevenMore = stockOut(owner, rice.id(), new BigDecimal("7"), "derica");
        assertThat(sevenMore.movement().quantity()).isEqualByComparingTo("4.2");
        assertThat(sevenMore.product().quantityOnHand()).isEqualByComparingTo("152.8");

        // Re-weighed: from now on only (D-U4). The past still reads 5 dericas, 3 kg.
        ProductResponse reweighed = update(owner, rice.id(),
                List.of(new ProductMeasure("Derica", new BigDecimal("0.65")), new ProductMeasure("Cup", new BigDecimal("0.2"))), null);
        assertThat(reweighed.measures()).extracting(ProductMeasure::size).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.65"), new BigDecimal("0.2"));
        StockMovementResponse first = history(owner, rice.id()).stream()
                .filter(movement -> movement.id().equals(used.movement().id())).findFirst().orElseThrow();
        assertThat(first.quantity()).isEqualByComparingTo("3");
        assertThat(first.enteredQuantity()).isEqualByComparingTo("5");
        assertThat(first.enteredUnit()).isEqualTo("Derica");
        assertThat(stockOut(owner, rice.id(), new BigDecimal("2"), "Derica").movement().quantity()).isEqualByComparingTo("1.3");

        // Taken off: the cup was never used, so it goes; the derica was, so it's hidden - and the start unit, which
        // was the derica, goes back to the stock unit.
        ProductResponse trimmed = update(owner, rice.id(), List.of(), null);
        assertThat(trimmed.measures()).singleElement().satisfies(measure -> {
            assertThat(measure.name()).isEqualTo("Derica");
            assertThat(measure.hiddenNow()).isTrue();
        });
        assertThat(trimmed.usedInUnit()).isNull();
        assertThat(trimmed.unitOptions()).noneMatch(UnitOption::isMeasure);
        // A phone that saved "1 derica" before it was hidden still records it.
        assertThat(stockOut(owner, rice.id(), BigDecimal.ONE, "Derica").movement().quantity()).isEqualByComparingTo("0.65");
    }

    @Test
    void aMeasureMustHaveASizeANameOfItsOwnAndAStockUnitToBeANumberOf() {
        TenantLoginResponse owner = signup("Measure Rules");
        assertThat(createRefused(owner, "KG", List.of(new ProductMeasure("Bag", new BigDecimal("50")))).message())
                .contains("already a unit or a pack");
        assertThat(createRefused(owner, "KG", List.of(new ProductMeasure("Mudu", BigDecimal.ZERO))).message())
                .isEqualTo("Say how much one Mudu holds.");
        assertThat(createRefused(owner, "KG", List.of(new ProductMeasure("Mudu", new BigDecimal("1.2345")))).message())
                .contains("3 decimal places");
        assertThat(createRefused(owner, null, List.of(new ProductMeasure("Mudu", BigDecimal.ONE))).message())
                .contains("counted in first");
        ProductResponse beans = create(owner, "Beans", "KG", null, null, List.of(new ProductMeasure("Mudu", new BigDecimal("1.4"))), null);
        ResponseEntity<ApiError> notOne = updateRaw(owner, beans.id(), null, "Congo", ApiError.class);
        assertThat(notOne.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notOne.getBody().message()).isEqualTo("“Congo” isn't one of this product's units.");
    }

    @Test
    void aMeasureOfPiecesStillRecordsOnlyWholePieces() {
        TenantLoginResponse owner = signup("Egg Plates");
        ProductResponse eggs = create(owner, "Eggs", "PIECE", null, null, List.of(new ProductMeasure("Plate", new BigDecimal("2"))), null);
        stockIn(owner, eggs.id(), new BigDecimal("30"), "PIECE");
        assertThat(stockOut(owner, eggs.id(), new BigDecimal("1.5"), "Plate").movement().quantity()).isEqualByComparingTo("3");
        ResponseEntity<ApiError> half = restTemplate.exchange(
                "/api/products/" + eggs.id() + "/stock/stock-out", HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(new BigDecimal("1.25"), null, null, "Plate", null), authHeaders(owner)),
                ApiError.class);
        assertThat(half.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(half.getBody().message()).contains("whole");
    }

    // ---------------------------------------------------------------------------------------- helpers

    private ProductResponse create(
            TenantLoginResponse owner, String name, String unit, String pack, BigDecimal packSize,
            List<ProductMeasure> measures, String usedIn) {
        ResponseEntity<ProductResponse> response = createRaw(owner, name, unit, pack, packSize, measures, usedIn, ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ApiError createRefused(TenantLoginResponse owner, String unit, List<ProductMeasure> measures) {
        ResponseEntity<ApiError> response = createRaw(owner, "Refused", unit, null, null, measures, null, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return response.getBody();
    }

    private <T> ResponseEntity<T> createRaw(
            TenantLoginResponse owner, String name, String unit, String pack, BigDecimal packSize,
            List<ProductMeasure> measures, String usedIn, Class<T> type) {
        CreateProductRequest request = new CreateProductRequest(name, "PM-" + UUID.randomUUID().toString().substring(0, 8),
                null, null, null, unit, pack, packSize, null, null, null, null, measures, usedIn);
        return restTemplate.exchange("/api/products", HttpMethod.POST, multipart(owner, request), type);
    }

    private ProductResponse update(TenantLoginResponse owner, UUID id, List<ProductMeasure> measures, String usedIn) {
        ResponseEntity<ProductResponse> response = updateRaw(owner, id, measures, usedIn, ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> updateRaw(
            TenantLoginResponse owner, UUID id, List<ProductMeasure> measures, String usedIn, Class<T> type) {
        UpdateProductRequest request = new UpdateProductRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, measures, usedIn);
        return restTemplate.exchange("/api/products/" + id, HttpMethod.PUT, multipart(owner, request), type);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(TenantLoginResponse owner, Object request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, part));
        HttpHeaders headers = authHeaders(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private void stockIn(TenantLoginResponse owner, UUID productId, BigDecimal quantity, String unit) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, null, null, unit, null, null, null, null, null, null),
                        authHeaders(owner)),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private StockMutationResponse stockOut(TenantLoginResponse owner, UUID productId, BigDecimal quantity, String unit) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out", HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, null, null, unit, null), authHeaders(owner)),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<StockMovementResponse> history(TenantLoginResponse owner, UUID productId) {
        ResponseEntity<PageOf> page = restTemplate.exchange(
                "/api/products/" + productId + "/stock/history?size=50", HttpMethod.GET, new HttpEntity<>(authHeaders(owner)),
                PageOf.class);
        return page.getBody().content();
    }

    private record PageOf(List<StockMovementResponse> content) {
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
