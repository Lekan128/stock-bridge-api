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
 * Areas, Part 3.6 (STOCK_AREAS_PART_3_6.md): reports by area. Stock worth, the summary, the movements list and
 * its summary cover the areas asked for (a row from before areas counting in the original area); absent, the
 * whole branch as before; an area the person doesn't work in, or another company's, is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaReportIntegrationTest {

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
    void stockWorthAndTheSummaryCoverTheAreasAskedFor() {
        Session s = session("Report Worth");
        stockIn(s, 10, "100");
        Areas a = areas(s, "Kitchen", true);
        UUID bar = addArea(s, "Bar", true);
        move(s, a.store(), a.other(), 4);

        assertThat(worth(s, "")).isEqualByComparingTo("1000");
        assertThat(worth(s, "?areaIds=" + a.other())).isEqualByComparingTo("400");
        assertThat(worth(s, "?areaIds=" + a.store())).isEqualByComparingTo("600");
        assertThat(worth(s, "?areaIds=" + a.store() + "&areaIds=" + a.other())).isEqualByComparingTo("1000");

        // The delivery was the Store's (before areas: its original area): units in there, none in the Kitchen.
        assertThat(((Number) summary(s, "?areaIds=" + a.store()).get("totalUnitsIn")).intValue()).isEqualTo(10);
        assertThat(((Number) summary(s, "?areaIds=" + a.other()).get("totalUnitsIn")).intValue()).isZero();
        assertThat(((Number) summary(s, "").get("totalUnitsIn")).intValue()).isEqualTo(10);
        // In an area: never "low" (levels per area come with Part 8), and not "out" of what it never had.
        Map<?, ?> atBar = summary(s, "?areaIds=" + bar);
        assertThat(((Number) atBar.get("outOfStockProductCount")).intValue()).isZero();
        assertThat(((Number) atBar.get("lowStockProductCount")).intValue()).isZero();
        assertThat(((Number) summary(s, "?areaIds=" + a.other()).get("outOfStockProductCount")).intValue()).isZero();
        // Had and ran out of: out.
        move(s, a.other(), bar, 2);
        move(s, bar, a.other(), 2);
        assertThat(((Number) summary(s, "?areaIds=" + bar).get("outOfStockProductCount")).intValue()).isEqualTo(1);
        // The product's area figures say which areas carry it.
        Map<?, ?> product = get(s, "/api/products/" + s.productId());
        Map<String, Object> carried = new HashMap<>();
        for (Object figure : (List<?>) product.get("areaStock")) {
            carried.put((String) ((Map<?, ?>) figure).get("areaId"), ((Map<?, ?>) figure).get("carried"));
        }
        assertThat(carried).containsEntry(a.store().toString(), true).containsEntry(a.other().toString(), true)
                .containsEntry(bar.toString(), true);
        UUID newArea = addArea(s, "Pantry", true);
        Map<?, ?> again = get(s, "/api/products/" + s.productId());
        assertThat(((List<?>) again.get("areaStock")).stream().map(f -> (Map<?, ?>) f)
                .filter(f -> newArea.toString().equals(f.get("areaId"))).findFirst().orElseThrow().get("carried")).isEqualTo(false);
    }

    @Test
    void theMovementsListAndItsSummaryCoverTheAreasAskedFor() {
        Session s = session("Report Movements");
        stockIn(s, 10, "100");
        Areas a = areas(s, "Kitchen", true);
        move(s, a.store(), a.other(), 4);
        assertThat(post(s, "/api/products/" + s.productId() + "/stock/stock-out",
                Map.of("quantity", 1, "areaId", a.other().toString()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(types(s, "/api/stock/movements?productId=" + s.productId() + "&areaIds=" + a.other()))
                .containsExactly("OUT", "MOVE");
        assertThat(types(s, "/api/stock/movements?productId=" + s.productId() + "&areaIds=" + a.store()))
                .containsExactly("MOVE", "IN");
        assertThat(types(s, "/api/stock/movements?productId=" + s.productId())).containsExactly("OUT", "MOVE", "IN");
        Map<?, ?> kitchen = get(s, "/api/stock/movements/summary?areaIds=" + a.other());
        assertThat(((Number) kitchen.get("outQuantity")).intValue()).isEqualTo(1);
        assertThat(((Number) kitchen.get("inQuantity")).intValue()).isZero();
    }

    @Test
    void anAreaThePersonDoesntWorkInOrAnotherCompanysIsRefused() {
        Session s = session("Report Refused");
        Areas a = areas(s, "Kitchen", true);
        createPerson(s, "cook", List.of(a.other()));
        Session cook = login(s, "cook");
        assertThat(raw(cook, "/api/stock/movements?areaIds=" + a.store()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(cook, "/api/stock/movements?areaIds=" + a.other()).getStatusCode()).isEqualTo(HttpStatus.OK);
        Session other = session("Report Other");
        UUID theirs = area(mainBranch(other), "Store");
        assertThat(raw(s, "/api/stock/movements?areaIds=" + theirs).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private BigDecimal worth(Session s, String query) {
        return new BigDecimal(String.valueOf(get(s, "/api/analytics/stock-worth" + query).get("valueAtCost")));
    }

    private Map<?, ?> summary(Session s, String query) {
        return get(s, "/api/analytics/summary" + query);
    }

    private Map<?, ?> get(Session s, String path) {
        ResponseEntity<Map> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> raw(Session s, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), String.class);
    }

    private List<Object> types(Session s, String path) {
        return ((List<?>) get(s, path).get("content")).stream().map(r -> (Object) ((Map<?, ?>) r).get("movementType")).toList();
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
