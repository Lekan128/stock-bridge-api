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
 * Areas, Part 3.5 (STOCK_AREAS_PART_3_5.md): kitchen counts. Where stock is worked with, a count's shortfall is
 * what was used - a costed Used stock-out that is also the count; where it's stored, it's missing, as ever;
 * the sheet can say otherwise for one count; more is never "used"; counting twice adds nothing; a late
 * offline write behind it isn't counted twice; Undo puts it back; a move line can be used straight away; and
 * an area says what happens there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaCountIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void aKitchensShortCountIsUsedThereCostedAndCountingAgainAddsNothing() {
        Session s = session("Count Kitchen");
        stockIn(s, 10, "100");
        Areas a = kitchen(s);
        move(s, a.store(), a.other(), 6);

        count(s, a.other(), 4, null, null);
        Map<String, Object> used = jdbc.queryForMap(
                "SELECT movement_type, out_reason, quantity, is_count, area_id, cost_amount FROM stock_movements"
                        + " WHERE product_id = :p AND is_count",
                new MapSqlParameterSource("p", s.productId()));
        assertThat(used).containsEntry("movement_type", "OUT").containsEntry("out_reason", "USED")
                .containsEntry("quantity", new BigDecimal("2.000")).containsEntry("is_count", true).containsEntry("area_id", a.other());
        assertThat((BigDecimal) used.get("cost_amount")).isEqualByComparingTo("200");
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(branchFigure(s)).isEqualTo(8);
        // The list says it came from a count: "Used at Kitchen · count".
        Map<?, ?> rows = restTemplate.exchange("/api/stock/movements?productId=" + s.productId(), HttpMethod.GET,
                new HttpEntity<>(auth(s)), Map.class).getBody();
        Map<?, ?> latest = (Map<?, ?>) ((List<?>) rows.get("content")).getFirst();
        assertThat(latest.get("outReason")).isEqualTo("USED");
        assertThat(latest.get("count")).isEqualTo(true);

        // The same shelf again: nothing more used.
        count(s, a.other(), 4, null, null);
        assertThat(usedCount(s)).isEqualTo(1);
        // More than the books say is never "used": an adjustment up.
        count(s, a.other(), 5, null, null);
        assertThat(usedCount(s)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE product_id = :p AND movement_type = 'ADJUSTMENT' AND quantity = 1",
                new MapSqlParameterSource("p", s.productId()), Long.class)).isEqualTo(1);
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void aStoresShortCountIsMissingAndTheSheetCanSayOtherwiseForOneCount() {
        Session s = session("Count Store");
        stockIn(s, 10, "100");
        Areas a = kitchen(s);
        move(s, a.store(), a.other(), 6);

        count(s, a.store(), 3, null, null);
        assertThat(usedCount(s)).isZero();
        assertThat(areaFigure(a.store(), s)).isEqualTo(3);
        // This one went missing from the kitchen; and this one was used from the store.
        count(s, a.other(), 5, "MISSING", null);
        assertThat(usedCount(s)).isZero();
        count(s, a.store(), 2, "USED", null);
        assertThat(usedCount(s)).isEqualTo(1);
        assertThat(areaFigure(a.store(), s)).isEqualTo(2);
        assertThat(areaFigure(a.other(), s)).isEqualTo(5);
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void aLateOfflineStockOutBehindAKitchenCountIsNotCountedTwiceAndUndoPutsTheCountBack() {
        Session s = session("Count Late");
        stockIn(s, 10, "100");
        Areas a = kitchen(s);
        move(s, a.store(), a.other(), 6);
        count(s, a.other(), 4, null, null);

        // Used an hour ago on an offline phone: the count (a Used stock-out) already saw it gone.
        Map<String, Object> late = new HashMap<>();
        late.put("quantity", 1);
        late.put("areaId", a.other().toString());
        late.put("occurredAt", hoursAgo(1).toString());
        late.put("recordedOffline", true);
        ResponseEntity<String> lateOut = post(s, "/api/products/" + s.productId() + "/stock/stock-out", late, String.class);
        assertThat(lateOut.getStatusCode()).as(lateOut.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(areaHealthService.health().healthy()).isTrue();

        // A fresh count, undone straight away: the kitchen is back where it was.
        Session t = session("Count Undo");
        stockIn(t, 10, "100");
        Areas b = kitchen(t);
        move(t, b.store(), b.other(), 6);
        Map<?, ?> counted = count(t, b.other(), 4, null, null).getBody();
        String movementId = (String) ((Map<?, ?>) counted.get("movement")).get("id");
        ResponseEntity<String> undone = post(t, "/api/products/" + t.productId() + "/stock/movements/" + movementId + "/void",
                Map.of(), String.class);
        assertThat(undone.getStatusCode()).as(undone.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(b.other(), t)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movement_allocations a JOIN stock_movements m ON m.id = a.out_movement_id"
                        + " WHERE m.product_id = :p",
                new MapSqlParameterSource("p", t.productId()), Long.class)).isZero();
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void aMoveLineUsedStraightAwayIsUsedThereAndNeverJoinsTheKitchensStock() {
        Session s = session("Count Used Now");
        stockIn(s, 10, "100");
        Areas a = kitchen(s);
        Map<String, Object> body = body(UUID.randomUUID(), a.store(), a.other(), 3);
        body.put("lines", List.of(Map.of("productId", "PRODUCT", "quantity", 3, "usedNow", true)));
        ResponseEntity<String> moved = post(s, "/api/area-moves", body, String.class);
        assertThat(moved.getStatusCode()).as(moved.getBody()).isEqualTo(HttpStatus.CREATED);

        assertThat(areaFigure(a.other(), s)).isZero();
        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(jdbc.queryForMap(
                "SELECT out_reason, used_area_id, area_id FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", s.productId())))
                .containsEntry("out_reason", "USED").containsEntry("used_area_id", a.other()).containsEntry("area_id", a.store());
        Map<?, ?> page = restTemplate.exchange("/api/area-moves?productId=" + s.productId(), HttpMethod.GET,
                new HttpEntity<>(auth(s)), Map.class).getBody();
        Map<?, ?> move = (Map<?, ?>) ((List<?>) page.get("content")).getFirst();
        Map<?, ?> line = (Map<?, ?>) ((List<?>) move.get("lines")).getFirst();
        assertThat(line.get("used")).isEqualTo(true);
        // Among the stock movements it's the Used row, not a move as well.
        Map<?, ?> rows = restTemplate.exchange("/api/stock/movements?productId=" + s.productId(), HttpMethod.GET,
                new HttpEntity<>(auth(s)), Map.class).getBody();
        assertThat(List.copyOf((List<?>) rows.get("content"))).extracting(r -> (Object) ((Map<?, ?>) r).get("movementType"))
                .containsExactly("OUT", "IN");
        assertThat(areaHealthService.health().healthy()).isTrue();
    }

    @Test
    void anAreaSaysWhatHappensThereAndTheProductPageWhenItWasLastCounted() {
        Session s = session("Count Kind");
        stockIn(s, 5, null);
        UUID kitchen = addArea(s, "Kitchen", true);
        UUID housekeeping = addArea(s, "Housekeeping", false);
        assertThat(areaOf(s, kitchen).get("shortIsUsed")).isEqualTo(false);
        assertThat(patch(s, kitchen, Map.of("shortIsUsed", true)).get("shortIsUsed")).isEqualTo(true);
        // An area that uses what it's given has no counts: it's never "worked with".
        assertThat(patch(s, housekeeping, Map.of("shortIsUsed", true)).get("shortIsUsed")).isEqualTo(false);
        // Added as worked with.
        Map<String, Object> bar = new HashMap<>();
        bar.put("name", "Bar");
        bar.put("keepsStock", true);
        bar.put("shortIsUsed", true);
        post(s, "/api/branches/" + mainBranch(s) + "/areas", Map.of("areas", List.of(bar)), String.class);
        assertThat(areaOf(s, area(mainBranch(s), "Bar")).get("shortIsUsed")).isEqualTo(true);

        UUID store = area(mainBranch(s), "Store");
        count(s, store, 5, null, null);
        Map<?, ?> product = restTemplate.exchange("/api/products/" + s.productId(), HttpMethod.GET, new HttpEntity<>(auth(s)),
                Map.class).getBody();
        Map<?, ?> storeFigure = ((List<?>) product.get("areaStock")).stream().map(f -> (Map<?, ?>) f)
                .filter(f -> store.toString().equals(f.get("areaId"))).findFirst().orElseThrow();
        assertThat(storeFigure.get("lastCountedAt")).isNotNull();
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

    /** A storekeeper (stock in, out and count) limited to {@code areaIds}, or in every area for none. */
    private UUID createPerson(Session s, String username, List<UUID> areaIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        body.put("areaIds", areaIds.stream().map(UUID::toString).toList());
        ResponseEntity<Map> created = post(s, "/api/users", body, Map.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private Session person(Session s, String username, List<UUID> areaIds) {
        createPerson(s, username, areaIds);
        return login(s, username);
    }

    private Session login(Session s, String username) {
        return new Session(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(s.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class),
                s.productId());
    }

    private ResponseEntity<String> write(Session s, String kind, Map<String, Object> body) {
        return post(s, "/api/products/" + s.productId() + "/stock/" + kind, body, String.class);
    }

    private Map<?, ?> user(Session s, UUID id) {
        return restTemplate.exchange("/api/users/" + id, HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class).getBody();
    }

    private ResponseEntity<String> put(Session s, String path, Object body) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    /** Area name → whether this person works there, from the branch list. */
    private Map<String, Boolean> worksHere(Session s) {
        Map<?, ?> list = restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class).getBody();
        Map<String, Boolean> out = new HashMap<>();
        for (Object branch : (List<?>) list.get("branches")) {
            for (Object area : (List<?>) ((Map<?, ?>) branch).get("areas")) {
                out.put((String) ((Map<?, ?>) area).get("name"), (Boolean) ((Map<?, ?>) area).get("worksHere"));
            }
        }
        return out;
    }

    /** The Store, and a Kitchen where stock is worked with. */
    private Areas kitchen(Session s) {
        Map<String, Object> kitchen = new HashMap<>();
        kitchen.put("name", "Kitchen");
        kitchen.put("keepsStock", true);
        kitchen.put("shortIsUsed", true);
        ResponseEntity<String> added = post(s, "/api/branches/" + mainBranch(s) + "/areas", Map.of("areas", List.of(kitchen)), String.class);
        assertThat(added.getStatusCode()).as(added.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Areas(area(mainBranch(s), "Store"), area(mainBranch(s), "Kitchen"));
    }

    private ResponseEntity<Map> count(Session s, UUID areaId, int quantity, String shortAs, OffsetDateTime at) {
        Map<String, Object> body = new HashMap<>();
        body.put("countedQuantity", quantity);
        body.put("areaId", areaId.toString());
        if (shortAs != null) {
            body.put("shortAs", shortAs);
        }
        if (at != null) {
            body.put("countedAt", at.toString());
        }
        ResponseEntity<Map> response = post(s, "/api/products/" + s.productId() + "/stock/count", body, Map.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response;
    }

    private long usedCount(Session s) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE product_id = :p AND is_count AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", s.productId()), Long.class);
    }

    private Map<?, ?> areaOf(Session s, UUID areaId) {
        Map<?, ?> list = restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class).getBody();
        for (Object branch : (List<?>) list.get("branches")) {
            for (Object area : (List<?>) ((Map<?, ?>) branch).get("areas")) {
                if (areaId.toString().equals(((Map<?, ?>) area).get("id"))) {
                    return (Map<?, ?>) area;
                }
            }
        }
        throw new AssertionError("no area " + areaId);
    }

    private Map<?, ?> patch(Session s, UUID areaId, Map<String, Object> body) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange("/api/areas/" + areaId, HttpMethod.PATCH,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
