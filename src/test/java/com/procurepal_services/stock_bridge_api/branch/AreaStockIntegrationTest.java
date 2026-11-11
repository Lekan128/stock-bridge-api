package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.InsufficientStockErrorResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Areas inside a branch, Part 0 (STOCK_AREAS_PART_0.md): every branch has its Store, every write records
 * its area, a branch with one area behaves exactly as before, and every existing write is already right
 * at a branch with two.
 *
 * <p>Part 0 has no way to add a second area, so the two-area tests insert one with SQL - what Part 1's
 * "Add an area" will do, minus copying the branch's figures into the Store first, which these branches
 * don't need: each adds its areas before it records anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaStockIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    // ----------------------------------------------------------------------------- the first area

    @Test
    void everyBranchHasOneStoreThatHoldsItsHistoryAndTakesItsDeliveries() {
        Session s = session("Area Store");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");

        for (UUID branch : List.of(main, second)) {
            List<Map<String, Object>> areas = jdbc.queryForList(
                    "SELECT name, is_original, is_delivery, keeps_stock, is_active FROM areas WHERE branch_id = :b",
                    new MapSqlParameterSource("b", branch));
            assertThat(areas).singleElement().satisfies(area -> {
                assertThat(area.get("name")).isEqualTo("Store");
                assertThat(area).containsEntry("is_original", true).containsEntry("is_delivery", true)
                        .containsEntry("keeps_stock", true).containsEntry("is_active", true);
            });
        }
    }

    // ------------------------------------------------------------------- one area: nothing changes

    @Test
    void aBranchWithOneAreaRecordsEveryWriteInItsStoreAndKeepsNoAreaFigures() {
        Session s = session("Area One");
        UUID main = mainBranch(s);
        UUID store = area(main, "Store");

        stockIn(s, body(20));
        stockOut(s, body(5));
        StockMutationResponse counted = write(s, "count", Map.of("countedQuantity", 12), StockMutationResponse.class).getBody();
        voidWrite(s, counted.movement().id());

        assertThat(product(s).quantityOnHand()).isEqualByComparingTo("15");
        assertThat(branchFigure(main, s)).isEqualTo(15);
        List<UUID> areas = jdbc.queryForList(
                "SELECT DISTINCT area_id FROM stock_movements WHERE product_id = :p",
                new MapSqlParameterSource("p", s.productId()), UUID.class);
        assertThat(areas).containsExactly(store);
        // Its figure stays on the branch row; its one area's row only remembers when it was counted (2026-11-10: the
        // count list's "Counted today" at a branch with one area), holding no figure.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM area_stock WHERE product_id = :p AND quantity_on_hand <> 0",
                new MapSqlParameterSource("p", s.productId()), Long.class))
                .as("a branch with one area keeps its figure on the branch row only").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM area_stock WHERE product_id = :p AND area_id = :a AND last_counted_at IS NOT NULL",
                new MapSqlParameterSource().addValue("p", s.productId()).addValue("a", store), Long.class))
                .as("its count is remembered on its one area").isEqualTo(1L);
    }

    // -------------------------------------------------------------------------------- two areas

    @Test
    void aDeliveryNamingNoAreaArrivesInTheDeliveryArea() {
        Session s = session("Area Delivery");
        UUID main = mainBranch(s);
        UUID kitchen = addArea(main, "Kitchen", true);

        StockMutationResponse in = stockIn(s, body(10));

        assertThat(areaFigure(area(main, "Store"), s)).isEqualTo(10);
        assertThat(areaFigure(kitchen, s)).isZero();
        assertThat(branchFigure(main, s)).isEqualTo(10);
        assertThat(in.product().quantityOnHand()).isEqualByComparingTo("10");
    }

    /** D-E10: a stock-out with no area, at a branch with a Store and a Kitchen, is never a guess. */
    @Test
    void aStockOutNamingNoAreaIsRefusedOnceTheBranchHasTwo() {
        Session s = session("Area Required");
        UUID main = mainBranch(s);
        addArea(main, "Kitchen", true);
        stockIn(s, body(10));

        ResponseEntity<String> refused = write(s, "stock-out", body(2), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Choose the area this is for");
        assertThat(product(s).quantityOnHand()).isEqualByComparingTo("10");
    }

    /** "The Kitchen has 3": judged against the area's figure, not the branch's. */
    @Test
    void aStockOutIsJudgedAgainstItsArea() {
        Session s = session("Area Ceiling");
        UUID main = mainBranch(s);
        UUID store = area(main, "Store");
        UUID kitchen = addArea(main, "Kitchen", true);
        stockIn(s, body(10));
        stockIn(s, in(body(3), kitchen));

        ResponseEntity<InsufficientStockErrorResponse> refused =
                write(s, "stock-out", in(body(5), kitchen), InsufficientStockErrorResponse.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().availableQuantity()).isEqualByComparingTo("3");

        StockMutationResponse used = stockOut(s, in(body(2), kitchen));
        assertThat(areaFigure(kitchen, s)).isEqualTo(1);
        assertThat(areaFigure(store, s)).isEqualTo(10);
        assertThat(branchFigure(main, s)).isEqualTo(11);
        assertThat(used.product().quantityOnHand()).isEqualByComparingTo("11");
    }

    @Test
    void anAreaThatUsesWhatItGetsOrBelongsToAnotherBranchCantBeWrittenTo() {
        Session s = session("Area Refused");
        Session other = session("Area Other");
        UUID main = mainBranch(s);
        UUID housekeeping = addArea(main, "Housekeeping", false);
        UUID theirs = area(mainBranch(other), "Store");

        ResponseEntity<String> used = write(s, "stock-in", in(body(3), housekeeping), String.class);
        ResponseEntity<String> foreign = write(s, "stock-in", in(body(3), theirs), String.class);

        assertThat(used.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(used.getBody()).contains("Housekeeping uses stock up as soon as it arrives");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).contains("That area was not found");
        assertThat(product(s).quantityOnHand()).isZero();
    }

    /** A count of the Kitchen carries forward the Kitchen's later writes only, never the Store's. */
    @Test
    void aCountIsCarriedForwardPerArea() {
        Session s = session("Area Count");
        UUID main = mainBranch(s);
        UUID store = area(main, "Store");
        UUID kitchen = addArea(main, "Kitchen", true);
        stockIn(s, body(10, hoursAgo(5)));
        stockIn(s, in(body(4, hoursAgo(5)), kitchen));
        stockOut(s, in(body(2), store)); // now: after the count below was taken

        Map<String, Object> count = new HashMap<>();
        count.put("countedQuantity", 3);
        count.put("countedAt", hoursAgo(1).toString());
        count.put("areaId", kitchen.toString());
        ResponseEntity<StockMutationResponse> counted = write(s, "count", count, StockMutationResponse.class);

        assertThat(counted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(kitchen, s)).isEqualTo(3);
        assertThat(areaFigure(store, s)).isEqualTo(8);
        assertThat(branchFigure(main, s)).isEqualTo(11);
    }

    @Test
    void undoPutsTheFigureBackInTheAreaItCameFrom() {
        Session s = session("Area Undo");
        UUID main = mainBranch(s);
        UUID store = area(main, "Store");
        UUID kitchen = addArea(main, "Kitchen", true);
        stockIn(s, body(5));
        stockIn(s, in(body(6), kitchen));

        StockMutationResponse used = stockOut(s, in(body(2), kitchen));
        voidWrite(s, used.movement().id());

        assertThat(areaFigure(kitchen, s)).isEqualTo(6);
        assertThat(areaFigure(store, s)).isEqualTo(5);
        assertThat(branchFigure(main, s)).isEqualTo(11);
    }

    /** Until a waybill can name areas (Part 7) it leaves from, and arrives into, the delivery areas. */
    @Test
    void aTransferLeavesFromAndArrivesIntoTheDeliveryAreas() {
        Session s = session("Area Transfer");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");
        UUID mainStore = area(main, "Store");
        UUID mainKitchen = addArea(main, "Kitchen", true);
        UUID secondStore = area(second, "Store");
        UUID secondBar = addArea(second, "Bar", true);
        stockIn(s, at(body(10), main));
        stockIn(s, in(at(body(3), main), mainKitchen));

        Map<String, Object> send = new HashMap<>();
        send.put("id", UUID.randomUUID().toString());
        send.put("fromBranchId", main.toString());
        send.put("toBranchId", second.toString());
        send.put("lines", List.of(Map.of("productId", s.productId().toString(), "quantity", 4)));
        ResponseEntity<Map> sent = post(s, "/api/stock-transfers", send, Map.class);
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(areaFigure(mainStore, s)).isEqualTo(6);
        assertThat(areaFigure(mainKitchen, s)).isEqualTo(3);

        ResponseEntity<Map> received = post(s, "/api/stock-transfers/" + sent.getBody().get("id") + "/receipts",
                Map.of("id", UUID.randomUUID().toString(), "asSent", true), Map.class);
        assertThat(received.getStatusCode()).as(String.valueOf(received.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(secondStore, s)).isEqualTo(4);
        assertThat(areaFigure(secondBar, s)).isZero();
        assertThat(branchFigure(second, s)).isEqualTo(4);
        assertThat(product(s).quantityOnHand()).isEqualByComparingTo("13");
    }

    // ------------------------------------------------------------------------------- Area Health

    @Test
    void areaHealthIsCleanAndCatchesAnAreaFigureBrokenByHand() {
        Session s = session("Area Health");
        UUID main = mainBranch(s);
        UUID kitchen = addArea(main, "Kitchen", true);
        stockIn(s, in(body(5), kitchen));

        AreaHealthResponse clean = areaHealthService.health();
        assertThat(clean.healthy()).as(clean.toString()).isTrue();
        assertThat(clean.areaModeBranches()).isPositive();

        MapSqlParameterSource row = new MapSqlParameterSource().addValue("a", kitchen).addValue("p", s.productId());
        jdbc.update("UPDATE area_stock SET quantity_on_hand = quantity_on_hand + 1 WHERE area_id = :a AND product_id = :p", row);
        try {
            AreaHealthResponse broken = areaHealthService.health();
            assertThat(broken.areaSumMismatchProducts()).isPositive();
            assertThat(broken.healthy()).isFalse();
        } finally {
            jdbc.update("UPDATE area_stock SET quantity_on_hand = quantity_on_hand - 1 WHERE area_id = :a AND product_id = :p", row);
        }
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    // ------------------------------------------------------------------------------------ helpers

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    private static OffsetDateTime hoursAgo(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    private static Map<String, Object> body(int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        return body;
    }

    private static Map<String, Object> body(int quantity, OffsetDateTime occurredAt) {
        Map<String, Object> body = body(quantity);
        body.put("occurredAt", occurredAt.toString());
        return body;
    }

    private static Map<String, Object> at(Map<String, Object> body, UUID branchId) {
        body.put("branchId", branchId.toString());
        return body;
    }

    private static Map<String, Object> in(Map<String, Object> body, UUID areaId) {
        body.put("areaId", areaId.toString());
        return body;
    }

    private UUID mainBranch(Session s) {
        return jdbc.queryForObject("""
                SELECT b.id FROM branches b JOIN products p ON p.client_id = b.client_id
                WHERE p.id = :p AND b.is_default
                """, new MapSqlParameterSource("p", s.productId()), UUID.class);
    }

    private UUID addBranch(Session s, String name) {
        return jdbc.queryForObject("""
                INSERT INTO branches (client_id, name, is_default, is_active)
                SELECT p.client_id, :name, FALSE, TRUE FROM products p WHERE p.id = :p
                RETURNING id
                """, new MapSqlParameterSource().addValue("p", s.productId()).addValue("name", name), UUID.class);
    }

    private UUID addArea(UUID branchId, String name, boolean keepsStock) {
        return jdbc.queryForObject("""
                INSERT INTO areas (client_id, branch_id, name, keeps_stock)
                SELECT client_id, id, :name, :keepsStock FROM branches WHERE id = :b
                RETURNING id
                """, new MapSqlParameterSource().addValue("b", branchId).addValue("name", name)
                        .addValue("keepsStock", keepsStock), UUID.class);
    }

    private UUID area(UUID branchId, String name) {
        return jdbc.queryForObject("SELECT id FROM areas WHERE branch_id = :b AND name = :name",
                new MapSqlParameterSource().addValue("b", branchId).addValue("name", name), UUID.class);
    }

    private int branchFigure(UUID branchId, Session s) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branchId).addValue("p", s.productId()),
                Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private int areaFigure(UUID areaId, Session s) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM area_stock WHERE area_id = :a AND product_id = :p",
                new MapSqlParameterSource().addValue("a", areaId).addValue("p", s.productId()),
                Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private StockMutationResponse stockIn(Session s, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = write(s, "stock-in", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private StockMutationResponse stockOut(Session s, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = write(s, "stock-out", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void voidWrite(Session s, UUID movementId) {
        ResponseEntity<StockMutationResponse> response =
                write(s, "movements/" + movementId + "/void", Map.of(), StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> write(Session s, String action, Object body, Class<T> type) {
        return post(s, "/api/products/" + s.productId() + "/stock/" + action, body, type);
    }

    private <T> ResponseEntity<T> post(Session s, String path, Object body, Class<T> type) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ProductResponse product(Session s) {
        return restTemplate
                .exchange("/api/products/" + s.productId(), HttpMethod.GET, new HttpEntity<>(auth(s)), ProductResponse.class)
                .getBody();
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
                new CreateProductRequest("Product " + unique.substring(0, 6), "A-" + unique.substring(0, 8), null,
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
