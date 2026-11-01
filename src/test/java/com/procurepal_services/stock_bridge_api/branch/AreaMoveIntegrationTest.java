package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.InsufficientStockErrorResponse;
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
 * Areas, Part 2 (STOCK_AREAS_PART_2.md): moving stock between the areas of a branch, giving it to an area
 * that uses what it's given, the refusals, a resend, a late move behind a count, counts carrying moves
 * forward, Undo, and the list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaMoveIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private RoleRepository roleRepository;

    // ------------------------------------------------------------------------------------- moving

    /** Store → Kitchen: two area figures change, and nothing else - no ledger row, no lot, no cost. */
    @Test
    void aMoveChangesOnlyTheTwoAreasFigures() {
        Session s = session("Move Plain");
        stockIn(s, 10, "250");
        Areas a = areas(s, "Kitchen", true);
        long ledgerBefore = movements(s);

        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);

        assertThat(moved.fromAreaName()).isEqualTo("Store");
        assertThat(moved.toAreaName()).isEqualTo("Kitchen");
        assertThat(moved.status()).isEqualTo("DONE");
        assertThat(moved.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(4);
            assertThat(line.used()).isFalse();
        });
        assertThat(moved.undoUntil()).isNotNull();
        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertThat(product(s).quantityOnHand()).isEqualTo(10);
        assertThat(movements(s)).as("a move is not a ledger row").isEqualTo(ledgerBefore);
        AreaHealthResponse health = areaHealthService.health();
        assertThat(health.healthy()).as(health.toString()).isTrue();
    }

    /** A give to Housekeeping is a Used stock-out at the Store, costed, and says where it went. */
    @Test
    void aGiveToAnAreaThatUsesWhatItsGivenIsAUsedStockOutTaggedWithIt() {
        Session s = session("Move Give");
        stockIn(s, 10, "250");
        Areas a = areas(s, "Housekeeping", false);

        AreaMoveResponse given = move(s, a.store(), a.other(), 3);

        assertThat(given.toKeepsStock()).isFalse();
        assertThat(given.lines()).singleElement().satisfies(line -> assertThat(line.used()).isTrue());
        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(branchFigure(s)).isEqualTo(7);
        assertThat(product(s).quantityOnHand()).isEqualTo(7);
        Map<String, Object> out = jdbc.queryForMap("""
                SELECT movement_type, out_reason, area_id, used_area_id, cost_amount, quantity FROM stock_movements
                WHERE product_id = :p AND movement_type = 'OUT'
                """, new MapSqlParameterSource("p", s.productId()));
        assertThat(out).containsEntry("out_reason", "USED").containsEntry("area_id", a.store())
                .containsEntry("used_area_id", a.other()).containsEntry("quantity", 3);
        assertThat((BigDecimal) out.get("cost_amount")).isEqualByComparingTo("750");
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void aMoveIsRefusedForTooLittleTheSameAreaAUsedFromAClosedToOrAnotherBranchsArea() {
        Session s = session("Move Refused");
        Session other = session("Move Elsewhere");
        stockIn(s, 5, null);
        Areas a = areas(s, "Kitchen", true);
        UUID housekeeping = addArea(s, "Housekeeping", false);
        UUID bar = addArea(s, "Bar", true);
        post(s, "/api/areas/" + bar + "/close", null, String.class);
        UUID theirs = area(mainBranch(other), "Store");

        ResponseEntity<InsufficientStockErrorResponse> tooMuch = raw(s, a.store(), a.other(), 6, InsufficientStockErrorResponse.class);
        ResponseEntity<String> same = raw(s, a.store(), a.store(), 1, String.class);
        ResponseEntity<String> fromUsed = raw(s, housekeeping, a.other(), 1, String.class);
        ResponseEntity<String> toClosed = raw(s, a.store(), bar, 1, String.class);
        ResponseEntity<String> toTheirs = raw(s, a.store(), theirs, 1, String.class);

        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooMuch.getBody().availableQuantity()).isEqualTo(5);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(same.getBody()).contains("Choose two different areas");
        assertThat(fromUsed.getBody()).contains("Housekeeping uses stock up as soon as it arrives");
        assertThat(toClosed.getBody()).contains("Bar is closed.");
        assertThat(toTheirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(areaFigure(a.store(), s)).isEqualTo(5);
        assertThat(moves(s)).isZero();
    }

    /** A resend after a lost reply, or from a phone that came back online, is recorded once. */
    @Test
    void theSameMoveSentTwiceIsRecordedOnce() {
        Session s = session("Move Twice");
        stockIn(s, 10, null);
        Areas a = areas(s, "Kitchen", true);
        Map<String, Object> body = body(UUID.randomUUID(), a.store(), a.other(), 4);

        assertThat(post(s, "/api/area-moves", body, AreaMoveResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(s, "/api/area-moves", body, AreaMoveResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(moves(s)).isEqualTo(1);
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
    }

    // ------------------------------------------------------------------------------------- counts

    /** A count of the Kitchen taken before a move into it is carried forward by that move. */
    @Test
    void aCountIsCarriedForwardByTheAreasLaterMoves() {
        Session s = session("Move Count");
        stockIn(s, 10, null);
        Areas a = areas(s, "Kitchen", true);
        move(s, a.store(), a.other(), 4); // now: after the count below was taken

        Map<String, Object> count = new HashMap<>();
        count.put("countedQuantity", 1);
        count.put("countedAt", hoursAgo(1).toString());
        count.put("areaId", a.other().toString());
        assertThat(post(s, "/api/products/" + s.productId() + "/stock/count", count, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(areaFigure(a.other(), s)).as("counted 1, then 4 moved in").isEqualTo(5);
        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    /**
     * A move typed offline arrives after the Store was counted: the count already saw the 4 gone, so the
     * Store isn't taken down again - and the Kitchen still gets them.
     */
    @Test
    void aLateMoveBehindACountIsNotCountedTwice() {
        Session s = session("Move Late");
        stockIn(s, 10, null);
        Areas a = areas(s, "Kitchen", true);
        Map<String, Object> count = new HashMap<>();
        count.put("countedQuantity", 6);
        count.put("areaId", a.store().toString());
        post(s, "/api/products/" + s.productId() + "/stock/count", count, String.class);

        Map<String, Object> late = body(UUID.randomUUID(), a.store(), a.other(), 4);
        late.put("occurredAt", hoursAgo(1).toString());
        late.put("recordedOffline", true);
        assertThat(post(s, "/api/area-moves", late, AreaMoveResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(branchFigure(s)).isEqualTo(10);
        AreaHealthResponse health = areaHealthService.health();
        assertThat(health.healthy()).as(health.toString()).isTrue();
    }

    // --------------------------------------------------------------------------------------- undo

    @Test
    void theMoverCanUndoAMoveOrAGiveStraightAway() {
        Session s = session("Move Undo");
        stockIn(s, 10, "100");
        Areas a = areas(s, "Kitchen", true);
        UUID housekeeping = addArea(s, "Housekeeping", false);

        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);
        AreaMoveResponse undone = post(s, "/api/area-moves/" + moved.id() + "/undo", null, AreaMoveResponse.class).getBody();
        assertThat(undone.status()).isEqualTo("VOIDED");
        assertThat(undone.undoUntil()).isNull();
        assertThat(areaFigure(a.store(), s)).isEqualTo(10);
        assertThat(areaFigure(a.other(), s)).isZero();

        AreaMoveResponse given = move(s, a.store(), housekeeping, 3);
        assertThat(post(s, "/api/area-moves/" + given.id() + "/undo", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(a.store(), s)).isEqualTo(10);
        assertThat(product(s).quantityOnHand()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", s.productId()), Long.class)).isZero();
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void undoIsRefusedToSomeoneElseAndOnceSomethingWasRecordedSince() {
        Session s = session("Move Undo Refused");
        stockIn(s, 10, null);
        Areas a = areas(s, "Kitchen", true);
        TenantLoginResponse storekeeper = storekeeper(s, "bisi");

        AreaMoveResponse first = move(s, a.store(), a.other(), 4);
        ResponseEntity<String> byOther = restTemplate.exchange("/api/area-moves/" + first.id() + "/undo", HttpMethod.POST,
                new HttpEntity<>(null, bearer(storekeeper.tokens().accessToken())), String.class);
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(byOther.getBody()).contains("Only the person who moved this can undo it.");

        stockIn(s, 1, null);
        ResponseEntity<String> afterWrite = post(s, "/api/area-moves/" + first.id() + "/undo", null, String.class);
        assertThat(afterWrite.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterWrite.getBody()).contains("Something else has been recorded for these products since");
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
    }

    // ------------------------------------------------------------------------------------ reading

    @Test
    void movesAreListedNewestFirstAndByProduct() {
        Session s = session("Move List");
        stockIn(s, 10, null);
        Areas a = areas(s, "Kitchen", true);
        move(s, a.store(), a.other(), 1);
        AreaMoveResponse second = move(s, a.store(), a.other(), 2);

        Map<?, ?> page = restTemplate.exchange("/api/area-moves?productId=" + s.productId(), HttpMethod.GET,
                new HttpEntity<>(auth(s)), Map.class).getBody();
        List<?> content = (List<?>) page.get("content");
        assertThat(content).hasSize(2);
        assertThat(((Map<?, ?>) content.getFirst()).get("id")).isEqualTo(second.id().toString());
        assertThat(((Map<?, ?>) content.getFirst()).get("movedByName")).isNotNull();
    }

    @Test
    void movesReadAmongTheStockMovementsAndTheProductsHistory() {
        Session s = session("Move Rows");
        stockIn(s, 10, "100");
        Areas a = areas(s, "Kitchen", true);
        UUID housekeeping = addArea(s, "Housekeeping", false);
        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);
        move(s, a.store(), housekeeping, 1);
        AreaMoveResponse undone = move(s, a.store(), a.other(), 2);
        post(s, "/api/area-moves/" + undone.id() + "/undo", Map.of(), AreaMoveResponse.class);

        // Newest first: the give is a Used stock-out (not a move row), then the move, then the delivery;
        // the undone move is not a row.
        List<Object> list = rows(s, "/api/stock/movements?productId=" + s.productId());
        assertThat(list).extracting(row -> (Object) ((Map<?, ?>) row).get("movementType")).containsExactly("OUT", "MOVE", "IN");
        Map<?, ?> row = (Map<?, ?>) list.get(1);
        assertThat(row.get("areaId")).isEqualTo(a.store().toString());
        assertThat(row.get("toAreaId")).isEqualTo(a.other().toString());
        assertThat(row.get("moveId")).isEqualTo(moved.id().toString());
        assertThat(row.get("areaName")).isEqualTo("Store");
        assertThat(row.get("toAreaName")).isEqualTo("Kitchen");
        assertThat(row.get("quantity")).isEqualTo(4);
        assertThat(row.get("createdByName")).isNotNull();
        assertThat(row.get("lineValue")).isNull();

        assertThat(rows(s, "/api/products/" + s.productId() + "/stock/history"))
                .extracting(r -> (Object) ((Map<?, ?>) r).get("movementType")).containsExactly("OUT", "MOVE", "IN");
        // A type, supplier or reason chosen leaves moves out; pages split the rows merged.
        assertThat(rows(s, "/api/stock/movements?movementType=IN&productId=" + s.productId())).hasSize(1);
        assertThat(rows(s, "/api/stock/movements?size=2&page=1&productId=" + s.productId()))
                .extracting(r -> (Object) ((Map<?, ?>) r).get("movementType")).containsExactly("IN");
        Map<?, ?> page = restTemplate.exchange("/api/stock/movements?size=2&productId=" + s.productId(), HttpMethod.GET,
                new HttpEntity<>(auth(s)), Map.class).getBody();
        assertThat(((Number) page.get("totalElements")).intValue()).isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------ helpers

    private List<Object> rows(Session s, String path) {
        Map<?, ?> page = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class).getBody();
        return List.copyOf((List<?>) page.get("content"));
    }

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    private record Areas(UUID store, UUID other) {
    }

    private static OffsetDateTime hoursAgo(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    private Areas areas(Session s, String name, boolean keepsStock) {
        UUID other = addArea(s, name, keepsStock);
        return new Areas(area(mainBranch(s), "Store"), other);
    }

    private UUID addArea(Session s, String name, boolean keepsStock) {
        Map<String, Object> area = new HashMap<>();
        area.put("name", name);
        area.put("keepsStock", keepsStock);
        ResponseEntity<String> added = post(s, "/api/branches/" + mainBranch(s) + "/areas", Map.of("areas", List.of(area)), String.class);
        assertThat(added.getStatusCode()).as(added.getBody()).isEqualTo(HttpStatus.CREATED);
        return area(mainBranch(s), name);
    }

    private static Map<String, Object> body(UUID id, UUID from, UUID to, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id.toString());
        body.put("fromAreaId", from.toString());
        body.put("toAreaId", to.toString());
        body.put("lines", List.of(Map.of("productId", "PRODUCT", "quantity", quantity)));
        return body;
    }

    private AreaMoveResponse move(Session s, UUID from, UUID to, int quantity) {
        ResponseEntity<String> response = raw(s, from, to, quantity, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = response.getBody().replaceAll("^.*?\"id\":\"([0-9a-f-]{36})\".*$", "$1");
        return restTemplate.exchange("/api/area-moves/" + id, HttpMethod.GET, new HttpEntity<>(auth(s)), AreaMoveResponse.class)
                .getBody();
    }

    private <T> ResponseEntity<T> raw(Session s, UUID from, UUID to, int quantity, Class<T> type) {
        return post(s, "/api/area-moves", body(UUID.randomUUID(), from, to, quantity), type);
    }

    private UUID mainBranch(Session s) {
        return jdbc.queryForObject("""
                SELECT b.id FROM branches b JOIN products p ON p.client_id = b.client_id
                WHERE p.id = :p AND b.is_default
                """, new MapSqlParameterSource("p", s.productId()), UUID.class);
    }

    private UUID area(UUID branchId, String name) {
        return jdbc.queryForObject("SELECT id FROM areas WHERE branch_id = :b AND name = :name",
                new MapSqlParameterSource().addValue("b", branchId).addValue("name", name), UUID.class);
    }

    private int areaFigure(UUID areaId, Session s) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM area_stock WHERE area_id = :a AND product_id = :p",
                new MapSqlParameterSource().addValue("a", areaId).addValue("p", s.productId()), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private int branchFigure(Session s) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", mainBranch(s)).addValue("p", s.productId()), Integer.class);
    }

    private long movements(Session s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = :p",
                new MapSqlParameterSource("p", s.productId()), Long.class);
    }

    private long moves(Session s) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM area_moves m JOIN area_move_lines l ON l.move_id = m.id WHERE l.product_id = :p
                """, new MapSqlParameterSource("p", s.productId()), Long.class);
    }

    private void stockIn(Session s, int quantity, String unitPrice) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        if (unitPrice != null) {
            body.put("unitPrice", unitPrice);
        }
        ResponseEntity<String> response = post(s, "/api/products/" + s.productId() + "/stock/stock-in", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> post(Session s, String path, Object body, Class<T> type) {
        // The move bodies name their product as "PRODUCT" until here, where the session's product is known.
        if (body instanceof Map<?, ?> map && map.get("lines") instanceof List<?> lines) {
            Map<String, Object> copy = new HashMap<>((Map<String, Object>) map);
            copy.put("lines", lines.stream().map(line -> {
                Map<String, Object> l = new HashMap<>((Map<String, Object>) line);
                if ("PRODUCT".equals(l.get("productId"))) {
                    l.put("productId", s.productId().toString());
                }
                return l;
            }).toList());
            body = copy;
        }
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ProductResponse product(Session s) {
        return restTemplate
                .exchange("/api/products/" + s.productId(), HttpMethod.GET, new HttpEntity<>(auth(s)), ProductResponse.class)
                .getBody();
    }

    private TenantLoginResponse storekeeper(Session s, String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        ResponseEntity<String> created = post(s, "/api/users", body, String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(s.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
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
        HttpHeaders headers = bearer(login.tokens().accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ProductResponse product = restTemplate
                .exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody();
        return new Session(login, product.id());
    }

    private HttpHeaders auth(Session s) {
        return bearer(s.login().tokens().accessToken());
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
