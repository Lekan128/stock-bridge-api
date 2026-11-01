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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Areas, Part 4 (STOCK_AREAS_PART_4.md): areas that confirm what arrives - a move waits, held in no area's
 * figure; confirmed whole, short (back or damaged), by an area that uses what it's given; cancelled, undone;
 * who may; a resend; a late confirmation behind a count; the count's carry-forward; Area Health throughout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaConfirmIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private RoleRepository roleRepository;

    /** The Store's figure drops at once; the Kitchen's only when the cook confirms; the branch's never moves. */
    @Test
    void aMoveWaitsInNoAreaUntilTheAreaConfirmsIt() {
        Session s = session("Confirm Whole");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Session cook = person(s, "cook", List.of(a.other()));

        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);

        assertThat(moved.status()).isEqualTo("WAITING");
        assertThat(moved.waits()).isTrue();
        assertThat(moved.canConfirm()).as("the owner works in every area").isTrue();
        assertThat(moved.canCancel()).isTrue();
        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(areaFigure(a.other(), s)).isZero();
        assertThat(branchFigure(s)).isEqualTo(10);
        assertHealthy();
        assertThat(product(s).areaStock()).filteredOn(figure -> figure.areaId().equals(a.other()))
                .singleElement().satisfies(figure -> assertThat(figure.waiting()).isEqualTo(4));
        assertThat(notifications(cook, "AREA_MOVE_WAITING")).isEqualTo(1);
        List<AreaMoveResponse> waiting = waiting(cook);
        assertThat(waiting).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(moved.id());
            assertThat(m.canConfirm()).isTrue();
            assertThat(m.canCancel()).as("the cook didn't move it").isFalse();
        });

        AreaMoveResponse confirmed = confirm(cook, moved.id(), Map.of());

        assertThat(confirmed.status()).isEqualTo("DONE");
        assertThat(confirmed.confirmedByName()).isEqualTo("cook");
        assertThat(confirmed.lines()).singleElement().satisfies(line -> assertThat(line.got()).isEqualTo(4));
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertThat(waiting(cook)).isEmpty();
        assertHealthy();
        // Stock movements: one Store → Kitchen row, for what arrived.
        assertThat(rows(s, "/api/stock/movements?productId=" + s.productId()))
                .filteredOn(row -> "MOVE".equals(((Map<?, ?>) row).get("movementType")))
                .singleElement().satisfies(row -> assertThat(((Map<?, ?>) row).get("quantity")).isEqualTo(4));
        // Sent again (a lost reply): the first stands.
        assertThat(confirm(cook, moved.id(), Map.of("lines", List.of(line(s, 0)))).status()).isEqualTo("DONE");
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
    }

    /** Got 3 of 5, the rest still in the Store; then got 1 of 4, the rest damaged - a Damaged stock-out there. */
    @Test
    void aShortConfirmationSendsTheRestBackOrRecordsItDamaged() {
        Session s = session("Confirm Short");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Session cook = person(s, "cook", List.of(a.other()));

        AreaMoveResponse first = move(s, a.store(), a.other(), 5);
        AreaMoveResponse back = confirm(cook, first.id(), Map.of("lines", List.of(line(s, 3)), "rest", "BACK"));

        assertThat(back.status()).isEqualTo("CONFIRMED_SHORT");
        assertThat(back.lines()).singleElement().satisfies(line -> {
            assertThat(line.got()).isEqualTo(3);
            assertThat(line.damaged()).isZero();
        });
        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(areaFigure(a.other(), s)).isEqualTo(3);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertThat(notifications(s, "AREA_MOVE_SHORT")).as("the mover hears").isEqualTo(1);
        assertHealthy();

        AreaMoveResponse second = move(s, a.store(), a.other(), 4);
        AreaMoveResponse damaged = confirm(cook, second.id(), Map.of("lines", List.of(line(s, 1)), "rest", "DAMAGED"));

        assertThat(damaged.lines()).singleElement().satisfies(line -> assertThat(line.damaged()).isEqualTo(3));
        assertThat(areaFigure(a.store(), s)).isEqualTo(3);
        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(branchFigure(s)).isEqualTo(7);
        Map<String, Object> out = jdbc.queryForMap("""
                SELECT out_reason, area_id, quantity FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'
                """, new MapSqlParameterSource("p", s.productId()));
        assertThat(out).containsEntry("out_reason", "DAMAGED").containsEntry("area_id", a.store()).containsEntry("quantity", 3);
        assertHealthy();
    }

    /** Housekeeping confirms too: what it got is recorded as used when it's confirmed, not when it's moved. */
    @Test
    void anAreaThatUsesWhatItsGivenRecordsUseWhenItConfirms() {
        Session s = session("Confirm Used");
        stockIn(s, 10);
        Areas a = areas(s, "Housekeeping", false, true);

        AreaMoveResponse moved = move(s, a.store(), a.other(), 3);

        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertThat(outs(s)).as("nothing used yet").isZero();
        assertHealthy();

        confirm(s, moved.id(), Map.of("lines", List.of(line(s, 2))));

        Map<String, Object> out = jdbc.queryForMap("""
                SELECT out_reason, area_id, used_area_id, quantity FROM stock_movements
                WHERE product_id = :p AND movement_type = 'OUT'
                """, new MapSqlParameterSource("p", s.productId()));
        assertThat(out).containsEntry("out_reason", "USED").containsEntry("area_id", a.store())
                .containsEntry("used_area_id", a.other()).containsEntry("quantity", 2);
        assertThat(areaFigure(a.store(), s)).isEqualTo(8);
        assertThat(branchFigure(s)).isEqualTo(8);
        assertHealthy();
    }

    /** Taken back while waiting, or undone: the stock is in the Store again, and there's nothing to confirm. */
    @Test
    void aWaitingMoveCanBeCancelledOrUndoneAndThenNotConfirmed() {
        Session s = session("Confirm Cancel");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Session cook = person(s, "cook", List.of(a.other()));

        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);
        assertThat(post(cook, "/api/area-moves/" + moved.id() + "/cancel", null, String.class).getStatusCode())
                .as("the cook didn't move it").isEqualTo(HttpStatus.CONFLICT);
        AreaMoveResponse cancelled = post(s, "/api/area-moves/" + moved.id() + "/cancel", null, AreaMoveResponse.class).getBody();

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(areaFigure(a.store(), s)).isEqualTo(10);
        ResponseEntity<String> late = post(cook, "/api/area-moves/" + moved.id() + "/confirm", Map.of(), String.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).contains("This move was taken back");
        assertHealthy();

        AreaMoveResponse again = move(s, a.store(), a.other(), 2);
        assertThat(again.undoUntil()).isNotNull();
        AreaMoveResponse undone = post(s, "/api/area-moves/" + again.id() + "/undo", null, AreaMoveResponse.class).getBody();
        assertThat(undone.status()).isEqualTo("VOIDED");
        assertThat(areaFigure(a.store(), s)).isEqualTo(10);
        assertThat(areaFigure(a.other(), s)).isZero();
        assertHealthy();
    }

    /** Only someone who works in the Kitchen confirms for it; switching it off leaves waiting moves waiting. */
    @Test
    void onlyTheAreasPeopleConfirmAndClosingWaitsForIt() {
        Session s = session("Confirm Who");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        UUID bar = addArea(s, "Bar", true, false);
        Session barman = person(s, "barman", List.of(bar));

        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);

        assertThat(post(barman, "/api/area-moves/" + moved.id() + "/confirm", Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(waiting(barman)).isEmpty();
        assertThat(patch(s, "/api/areas/" + a.other(), Map.of("confirms", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> close = post(s, "/api/areas/" + a.other() + "/close", null, String.class);
        assertThat(close.getBody()).contains("Kitchen has a move waiting to be confirmed");
        assertThat(get(s, "/api/area-moves/" + moved.id(), AreaMoveResponse.class).status()).isEqualTo("WAITING");
        assertThat(confirm(s, moved.id(), Map.of()).status()).isEqualTo("DONE");
        // Off now: the next move is instant.
        assertThat(move(s, a.store(), a.other(), 1).status()).isEqualTo("DONE");
        assertThat(areaFigure(a.other(), s)).isEqualTo(5);
        assertHealthy();
    }

    /**
     * Confirmed on a phone that was offline, before the Kitchen was counted: the count already saw what arrived,
     * so the Kitchen isn't raised again, and the branch keeps its figure.
     */
    @Test
    void aLateConfirmationBehindACountIsNotCountedTwice() {
        Session s = session("Confirm Late");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Map<String, Object> early = body(UUID.randomUUID(), a.store(), a.other(), 4);
        early.put("occurredAt", hoursAgo(2).toString());
        AreaMoveResponse moved = post(s, "/api/area-moves", early, AreaMoveResponse.class).getBody();
        count(s, a.other(), 4, null);

        Map<String, Object> late = new HashMap<>();
        late.put("occurredAt", hoursAgo(1).toString());
        late.put("recordedOffline", true);
        confirm(s, moved.id(), late);

        assertThat(areaFigure(a.other(), s)).isEqualTo(4);
        assertThat(areaFigure(a.store(), s)).isEqualTo(6);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertHealthy();
    }

    /** Counts taken before a move that waited are carried forward: the Store by the hold, the Kitchen by what arrived. */
    @Test
    void earlierCountsAreCarriedForwardByAMoveThatWaited() {
        Session s = session("Confirm Carry");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Map<String, Object> early = body(UUID.randomUUID(), a.store(), a.other(), 4);
        early.put("occurredAt", hoursAgo(2).toString());
        AreaMoveResponse moved = post(s, "/api/area-moves", early, AreaMoveResponse.class).getBody();
        confirm(s, moved.id(), Map.of("lines", List.of(line(s, 3))));

        count(s, a.other(), 1, hoursAgo(3));
        count(s, a.store(), 10, hoursAgo(3));

        assertThat(areaFigure(a.other(), s)).as("counted 1, then 3 arrived").isEqualTo(4);
        assertThat(areaFigure(a.store(), s)).as("counted 10, then 10 came in, 4 left and 1 came back").isEqualTo(17);
        assertHealthy();
    }

    // ------------------------------------------------------------------------------------ helpers

    private void assertHealthy() {
        AreaHealthResponse health = areaHealthService.health();
        assertThat(health.healthy()).as(health.toString()).isTrue();
    }

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    private record Areas(UUID store, UUID other) {
    }

    private static OffsetDateTime hoursAgo(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    private static Map<String, Object> line(Session s, int got) {
        return Map.of("productId", s.productId().toString(), "got", got);
    }

    private Areas areas(Session s, String name, boolean keepsStock, boolean confirms) {
        UUID other = addArea(s, name, keepsStock, confirms);
        return new Areas(area(mainBranch(s), "Store"), other);
    }

    private UUID addArea(Session s, String name, boolean keepsStock, boolean confirms) {
        Map<String, Object> area = new HashMap<>();
        area.put("name", name);
        area.put("keepsStock", keepsStock);
        area.put("confirms", confirms);
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
        ResponseEntity<AreaMoveResponse> response = post(s, "/api/area-moves", body(UUID.randomUUID(), from, to, quantity),
                AreaMoveResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AreaMoveResponse confirm(Session who, UUID moveId, Map<String, Object> body) {
        ResponseEntity<String> response = post(who, "/api/area-moves/" + moveId + "/confirm", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return get(who, "/api/area-moves/" + moveId, AreaMoveResponse.class);
    }

    private List<AreaMoveResponse> waiting(Session who) {
        return restTemplate.exchange("/api/area-moves/waiting", HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<List<AreaMoveResponse>>() {}).getBody();
    }

    private void count(Session s, UUID areaId, int counted, OffsetDateTime at) {
        Map<String, Object> count = new HashMap<>();
        count.put("countedQuantity", counted);
        count.put("areaId", areaId.toString());
        if (at != null) {
            count.put("countedAt", at.toString());
        }
        ResponseEntity<String> response = post(s, "/api/products/" + s.productId() + "/stock/count", count, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private long notifications(Session who, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = :u AND type = :t",
                new MapSqlParameterSource().addValue("u", who.login().user().id()).addValue("t", type), Long.class);
    }

    private long outs(Session s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", s.productId()), Long.class);
    }

    private List<Object> rows(Session s, String path) {
        Map<?, ?> page = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), Map.class).getBody();
        return List.copyOf((List<?>) page.get("content"));
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

    private void stockIn(Session s, int quantity) {
        ResponseEntity<String> response = post(s, "/api/products/" + s.productId() + "/stock/stock-in",
                Map.of("quantity", quantity, "unitPrice", "250"), String.class);
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

    private ResponseEntity<String> patch(Session s, String path, Object body) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private <T> T get(Session s, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), type).getBody();
    }

    private ProductResponse product(Session s) {
        return get(s, "/api/products/" + s.productId(), ProductResponse.class);
    }

    private Session person(Session s, String username, List<UUID> areaIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        body.put("areaIds", areaIds.stream().map(UUID::toString).toList());
        ResponseEntity<String> created = post(s, "/api/users", body, String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Session(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(s.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class),
                s.productId());
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
