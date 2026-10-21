package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.CostBasis;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UndoSaleResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.ProductLotResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
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
 * A sale on the ledger (SELLING_PRICE_PLAN.md sections 4.3, 4.3a): what a stock-out records about
 * money, and every way a sale is put right afterwards - all against the real API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SaleLedgerIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------- what a sale records ----

    @Test
    void aCartonSoldIsRecordedAtExactlyWhatWasChargedAndCostedFromItsDelivery() {
        Shop shop = shop("Carton Shop");
        UUID milk = cartonProduct(shop, "Milk");
        stockIn(shop, milk, 2, "CARTON", "4800");

        StockMovementResponse line = stockOut(shop, milk, body(1, "CARTON", "5000", null, null, null)).movement();

        assertThat(line.outReason()).isEqualTo(OutReason.SALE);
        assertThat(line.saleId()).isNotNull();
        assertThat(line.saleAmount()).isEqualByComparingTo("5000.00");
        // Per piece the price is 208.33 to the kobo; the sale amount never goes through it.
        assertThat(line.unitPriceAtTime().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("208.33");
        assertThat(line.costAmount()).isEqualByComparingTo("4800.00");
        assertThat(line.costBasis()).isEqualTo(CostBasis.FIFO);
    }

    @Test
    void aLineTotalIsKeptExactly() {
        Shop shop = shop("Line Total Shop");
        UUID soap = pieceProduct(shop, "Soap");
        stockIn(shop, soap, 10, null, "500");

        StockMovementResponse line = stockOut(shop, soap, body(6, null, null, null, null, "10000")).movement();

        assertThat(line.saleAmount()).isEqualByComparingTo("10000.00");
        assertThat(line.costAmount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void aStockOutWithNoPriceAndNoReasonIsExactlyWhatItWasBeforeExceptItsCost() {
        Shop shop = shop("Plain Out Shop");
        UUID rice = pieceProduct(shop, "Rice");
        stockIn(shop, rice, 10, null, "100");

        StockMovementResponse plain = stockOut(shop, rice, body(2, null, null, null, null, null)).movement();
        assertThat(plain.outReason()).isNull();
        assertThat(plain.saleId()).isNull();
        assertThat(plain.saleAmount()).isNull();
        assertThat(plain.costAmount()).isEqualByComparingTo("200.00");

        StockMovementResponse expired = stockOut(shop, rice, body(1, null, null, OutReason.EXPIRED, null, null)).movement();
        assertThat(expired.outReason()).isEqualTo(OutReason.EXPIRED);
        assertThat(expired.saleId()).isNull();
    }

    @Test
    void costIsAverageForAnUnpricedDeliveryAndUnknownWhenNothingHasACost() {
        Shop shop = shop("Cost Basis Shop");
        UUID beans = pieceProduct(shop, "Beans");
        stockIn(shop, beans, 10, null, "100");
        stockIn(shop, beans, 10, null, null);
        StockMovementResponse mixed = stockOut(shop, beans, body(15, null, "150", null, null, null)).movement();
        assertThat(mixed.costAmount()).isEqualByComparingTo("1500.00");
        assertThat(mixed.costBasis()).isEqualTo(CostBasis.AVERAGE);

        UUID gift = pieceProduct(shop, "Gift");
        stockIn(shop, gift, 5, null, null);
        StockMovementResponse unknown = stockOut(shop, gift, body(1, null, "300", null, null, null)).movement();
        assertThat(unknown.costAmount()).isNull();
        assertThat(unknown.costBasis()).isEqualTo(CostBasis.UNKNOWN);
        assertThat(unknown.saleAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void aLateOfflineSaleAlreadyInALaterCountStillRecordsItsMoney() {
        Shop shop = shop("Absorbed Shop");
        UUID oil = pieceProduct(shop, "Oil");
        stockIn(shop, oil, 10, null, "100");
        // Counted now: 7 on the shelf. The sale below happened half an hour ago on a phone that was
        // offline, so the count already saw the shelf without those 3.
        count(shop, oil, 7, OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> late = body(3, null, "150", null, null, null);
        late.put("occurredAt", minutesAgo(30).toString());
        late.put("recordedOffline", true);
        StockMovementResponse line = stockOut(shop, oil, late).movement();

        assertThat(line.saleAmount()).isEqualByComparingTo("450.00");
        assertThat(line.costAmount()).isEqualByComparingTo("300.00");
        assertThat(product(shop, oil).quantityOnHand()).isEqualTo(7);
    }

    @Test
    void linesSentWithOneSaleIdAreOneSale() {
        Shop shop = shop("Basket Shop");
        UUID a = pieceProduct(shop, "A");
        UUID b = pieceProduct(shop, "B");
        stockIn(shop, a, 10, null, "100");
        stockIn(shop, b, 10, null, "200");
        UUID saleId = UUID.randomUUID();

        stockOut(shop, a, body(2, null, "150", null, saleId, null));
        stockOut(shop, b, body(1, null, "250", null, saleId, null));

        SaleResponse sale = sale(shop, saleId);
        assertThat(sale.lineCount()).isEqualTo(2);
        assertThat(sale.total()).isEqualByComparingTo("550.00");
        assertThat(sale.unpricedLineCount()).isZero();
    }

    // ---------------------------------------------------------------------------- undo ----

    @Test
    void undoingASaleVoidsEveryLineAndTheSaleIsGone() {
        Shop shop = shop("Undo Shop");
        UUID a = pieceProduct(shop, "A");
        UUID b = pieceProduct(shop, "B");
        stockIn(shop, a, 10, null, "100");
        stockIn(shop, b, 10, null, "200");
        UUID saleId = UUID.randomUUID();
        stockOut(shop, a, body(2, null, "150", null, saleId, null));
        stockOut(shop, b, body(1, null, "250", null, saleId, null));

        UndoSaleResponse undo = post(shop, "/api/sales/" + saleId + "/undo", null, UndoSaleResponse.class).getBody();

        assertThat(undo.undoneCount()).isEqualTo(2);
        assertThat(undo.notUndoneCount()).isZero();
        assertThat(product(shop, a).quantityOnHand()).isEqualTo(10);
        assertThat(product(shop, b).quantityOnHand()).isEqualTo(10);
        assertThat(get(shop, "/api/sales/" + saleId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void undoSaysWhichLineItCouldNotUndoAndUndoesTheRest() {
        Shop shop = shop("Undo Partial Shop");
        UUID a = pieceProduct(shop, "A");
        UUID b = pieceProduct(shop, "B");
        stockIn(shop, a, 10, null, "100");
        stockIn(shop, b, 10, null, "200");
        UUID saleId = UUID.randomUUID();
        stockOut(shop, a, body(2, null, "150", null, saleId, null));
        stockOut(shop, b, body(1, null, "250", null, saleId, null));
        // Another sale of A since: A's line is no longer its latest write.
        stockOut(shop, a, body(1, null, "150", null, null, null));

        UndoSaleResponse undo = post(shop, "/api/sales/" + saleId + "/undo", null, UndoSaleResponse.class).getBody();

        assertThat(undo.undoneCount()).isEqualTo(1);
        assertThat(undo.lines()).filteredOn(line -> !line.undone()).singleElement()
                .satisfies(line -> assertThat(line.reason()).contains("recorded for this product since"));
    }

    // -------------------------------------------------------------------------- cancel ----

    @Test
    void cancellingALineGivesTheStockBackToTheSameDeliveryAndNetsTheMoneyOut() {
        Shop shop = shop("Cancel Shop");
        UUID rice = pieceProduct(shop, "Rice");
        stockIn(shop, rice, 10, null, "100");
        StockMovementResponse line = stockOut(shop, rice, body(4, null, "150", null, null, null)).movement();
        assertThat(lots(shop, rice)).singleElement().satisfies(lot -> assertThat(lot.remaining()).isEqualTo(6));

        SaleResponse cancelled = post(shop, "/api/sales/" + line.saleId() + "/cancel", null, SaleResponse.class).getBody();

        assertThat(cancelled.cancelledLineCount()).isEqualTo(1);
        assertThat(cancelled.total()).isEqualByComparingTo("0");
        assertThat(cancelled.lines().get(0).cancelled()).isTrue();
        assertThat(product(shop, rice).quantityOnHand()).isEqualTo(10);
        // The delivery has its 4 back - FIFO draws on it again in its proper place.
        assertThat(lots(shop, rice)).singleElement().satisfies(lot -> assertThat(lot.remaining()).isEqualTo(10));
        // Every report that sums the sale's rows sees nothing left of it.
        assertThat(saleSum(shop, line.saleId(), "sale_amount")).isEqualByComparingTo("0");
        assertThat(saleSum(shop, line.saleId(), "cost_amount")).isEqualByComparingTo("0");

        // The ledger still holds both rows: the sale and its opposite.
        assertThat(history(shop, rice))
                .filteredOn(row -> line.id().equals(row.reversesMovementId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.movementType()).isEqualTo(MovementType.ADJUSTMENT);
                    assertThat(row.quantity()).isEqualTo(4);
                    assertThat(row.saleAmount()).isEqualByComparingTo("-600.00");
                });
    }

    @Test
    void cancellingTwiceChangesNothingAndTheDatabaseRefusesASecondCancellation() {
        Shop shop = shop("Cancel Twice Shop");
        UUID rice = pieceProduct(shop, "Rice");
        stockIn(shop, rice, 10, null, "100");
        StockMovementResponse line = stockOut(shop, rice, body(4, null, "150", null, null, null)).movement();

        post(shop, "/api/sales/" + line.saleId() + "/cancel", null, SaleResponse.class);
        ResponseEntity<SaleResponse> again = post(shop, "/api/sales/" + line.saleId() + "/cancel", null, SaleResponse.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(product(shop, rice).quantityOnHand()).isEqualTo(10);

        // Even past the service: a line can be cancelled once, by constraint.
        UUID clientId = clientIdOf(shop);
        TenantContext.set(clientId);
        try {
            StockMovement original = stockMovementRepository.findByIdAndClientId(line.id(), clientId).orElseThrow();
            assertThatThrownBy(() -> stockMovementRepository.saveAndFlush(StockMovement.builder()
                            .product(productRepository.findByIdAndClientId(rice, clientId).orElseThrow())
                            .movementType(MovementType.ADJUSTMENT)
                            .quantity(4)
                            .saleId(original.getSaleId())
                            .reversesMovementId(original.getId())
                            .build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aCashierCannotCancelSomeoneElsesSaleButTheOwnerCan() {
        Shop shop = shop("Cancel Rights Shop");
        UUID rice = pieceProduct(shop, "Rice");
        stockIn(shop, rice, 10, null, "100");
        StockMovementResponse ownersSale = stockOut(shop, rice, body(2, null, "150", null, null, null)).movement();
        Shop cashier = cashier(shop);

        ResponseEntity<String> refused = post(cashier, "/api/sales/" + ownersSale.saleId() + "/cancel", null, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("someone else recorded");

        // Their own sale, the same day, they can.
        StockMovementResponse theirs = stockOut(cashier, rice, body(1, null, "150", null, null, null)).movement();
        assertThat(post(cashier, "/api/sales/" + theirs.saleId() + "/cancel", null, SaleResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------------- add a price ----

    @Test
    void aSaleSavedWithNoPriceCanBeGivenOneOnce() {
        Shop shop = shop("Add Price Shop");
        UUID oil = pieceProduct(shop, "Oil");
        stockIn(shop, oil, 10, null, "100");
        StockMovementResponse line = stockOut(shop, oil, body(3, null, null, OutReason.SALE, null, null)).movement();
        assertThat(line.saleAmount()).isNull();
        assertThat(sale(shop, line.saleId()).unpricedLineCount()).isEqualTo(1);

        SaleResponse priced = post(shop, "/api/sales/lines/" + line.id() + "/price",
                Map.of("unitPrice", 240), SaleResponse.class).getBody();
        assertThat(priced.total()).isEqualByComparingTo("720.00");
        assertThat(priced.unpricedLineCount()).isZero();
        assertThat(priced.lines().get(0).priceAdded()).isTrue();
        // No stock moved.
        assertThat(product(shop, oil).quantityOnHand()).isEqualTo(7);

        // The same price again is harmless; a different one is refused.
        assertThat(post(shop, "/api/sales/lines/" + line.id() + "/price", Map.of("lineTotal", 720), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post(shop, "/api/sales/lines/" + line.id() + "/price", Map.of("lineTotal", 900), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------------- helpers ----

    private record Shop(TenantLoginResponse login) {
    }

    private static Map<String, Object> body(
            int quantity, String unit, String unitPrice, OutReason reason, UUID saleId, String lineTotal) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        if (unit != null) body.put("unit", unit);
        if (unitPrice != null) body.put("unitPrice", new BigDecimal(unitPrice));
        if (reason != null) body.put("reason", reason.name());
        if (saleId != null) body.put("saleId", saleId.toString());
        if (lineTotal != null) body.put("lineTotal", new BigDecimal(lineTotal));
        return body;
    }

    private StockMutationResponse stockOut(Shop shop, UUID productId, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response =
                post(shop, "/api/products/" + productId + "/stock/stock-out", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void stockIn(Shop shop, UUID productId, int quantity, String unit, String unitPrice) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        if (unit != null) body.put("unit", unit);
        if (unitPrice != null) body.put("unitPrice", new BigDecimal(unitPrice));
        assertThat(post(shop, "/api/products/" + productId + "/stock/stock-in", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void count(Shop shop, UUID productId, int quantity, OffsetDateTime countedAt) {
        assertThat(post(shop, "/api/products/" + productId + "/stock/count",
                        Map.of("countedQuantity", quantity, "countedAt", countedAt.toString()), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private SaleResponse sale(Shop shop, UUID saleId) {
        return get(shop, "/api/sales/" + saleId, SaleResponse.class).getBody();
    }

    private ProductResponse product(Shop shop, UUID productId) {
        return get(shop, "/api/products/" + productId, ProductResponse.class).getBody();
    }

    private List<ProductLotResponse> lots(Shop shop, UUID productId) {
        return restTemplate.exchange("/api/products/" + productId + "/lots?open=false", HttpMethod.GET,
                        new HttpEntity<>(auth(shop)), new ParameterizedTypeReference<List<ProductLotResponse>>() {})
                .getBody();
    }

    private List<StockMovementResponse> history(Shop shop, UUID productId) {
        return restTemplate.exchange("/api/products/" + productId + "/stock/history?size=50", HttpMethod.GET,
                        new HttpEntity<>(auth(shop)), new ParameterizedTypeReference<TestPage<StockMovementResponse>>() {})
                .getBody()
                .content();
    }

    private BigDecimal saleSum(Shop shop, UUID saleId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM stock_movements WHERE client_id = ? AND sale_id = ?",
                BigDecimal.class, clientIdOf(shop), saleId);
    }

    private <T> ResponseEntity<T> post(Shop shop, String path, Object body, Class<T> type) {
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> get(Shop shop, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(shop)), type);
    }

    private UUID pieceProduct(Shop shop, String name) {
        return createProduct(shop, name, "PIECE", null, null);
    }

    private UUID cartonProduct(Shop shop, String name) {
        return createProduct(shop, name, "PIECE", "CARTON", new BigDecimal("24"));
    }

    private UUID createProduct(Shop shop, String name, String unit, String pack, BigDecimal packSize) {
        String sku = name.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, unit, pack, packSize, null), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    /** A second person in the shop with Stock in and Stock out only - no inventory management. */
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

    private UUID clientIdOf(Shop shop) {
        return jdbcTemplate.queryForObject("SELECT id FROM clients WHERE slug = ?", UUID.class, shop.login().user().clientIdentifier());
    }

    private static HttpHeaders auth(Shop shop) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.login().tokens().accessToken());
        return headers;
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private record TestPage<T>(List<T> content) {
    }
}
