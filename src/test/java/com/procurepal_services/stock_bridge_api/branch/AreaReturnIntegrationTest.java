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
 * Areas, Part 6 (STOCK_AREAS_PART_6.md): an area that uses stock up gives part of a give back - into the Store,
 * the Used netted, its lots restored - or reports it spoilt, changing no figure; never more than was given, within
 * 7 days, by its people only, a resend once, the Store and owner told once. And confirming short says what
 * happened, line by line, with a note.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaReturnIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private RoleRepository roleRepository;

    /** Housekeeping got 3 and gives 1 back: the Store has it again, the Used is netted at its cost, its lot restored. */
    @Test
    void aGiveBackPutsItInTheStoreAndNetsTheUse() {
        Session s = session("Return Back");
        stockIn(s, 10);
        Areas a = areas(s, "Housekeeping", false, false);
        Session maid = person(s, "maid", List.of(a.other()));
        AreaMoveResponse given = move(s, a.store(), a.other(), 3);
        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        UUID used = jdbc.queryForObject("SELECT used_movement_id FROM area_move_lines WHERE move_id = :id",
                new MapSqlParameterSource("id", given.id()), UUID.class);

        assertThat(given(maid)).singleElement().satisfies(move -> assertThat(move.canReturn()).isTrue());
        UUID id = UUID.randomUUID();
        AreaMoveResponse back = giveBackOrReport(maid, given.id(), returnBody(id, s, "GIVE_BACK", 1, null, "Not needed"));
        // Sent again (a lost reply): recorded once.
        giveBackOrReport(maid, given.id(), returnBody(id, s, "GIVE_BACK", 1, null, "Not needed"));

        assertThat(areaFigure(a.store(), s)).isEqualTo(8);
        assertThat(branchFigure(s)).isEqualTo(8);
        assertThat(back.lines()).singleElement().satisfies(line -> {
            assertThat(line.returns()).singleElement().satisfies(r -> {
                assertThat(r.kind()).isEqualTo("GIVE_BACK");
                assertThat(r.quantity()).isEqualByComparingTo("1");
                assertThat(r.note()).isEqualTo("Not needed");
                assertThat(r.byName()).isEqualTo("maid");
            });
            assertThat(line.returnable()).isEqualByComparingTo("2");
        });
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT movement_type, quantity, area_id, used_area_id, cost_amount, note FROM stock_movements
                WHERE reverses_movement_id = :used
                """, new MapSqlParameterSource("used", used));
        assertThat(row).containsEntry("movement_type", "ADJUSTMENT").containsEntry("quantity", new java.math.BigDecimal("1.000"))
                .containsEntry("area_id", a.store()).containsEntry("used_area_id", a.other());
        assertThat((BigDecimal) row.get("cost_amount")).isEqualByComparingTo("-250.00");
        assertThat((String) row.get("note")).isEqualTo("Given back by the Housekeeping: Not needed");
        // The delivery it drew on has the 1 again: 3 drawn, 1 restored.
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(al.quantity), 0) FROM stock_movement_allocations al
                JOIN stock_movements o ON o.id = al.out_movement_id WHERE o.product_id = :p
                """, new MapSqlParameterSource("p", s.productId()), Integer.class)).isEqualTo(2);
        assertThat(notifications(s, "AREA_RETURN")).as("the owner hears, once").isEqualTo(1);
        assertThat(notifications(maid, "AREA_RETURN")).isZero();

        // Another, later: a second give back of the same give.
        giveBackOrReport(maid, given.id(), returnBody(UUID.randomUUID(), s, "GIVE_BACK", 1, null, null));
        assertThat(areaFigure(a.store(), s)).isEqualTo(9);
        assertThat(get(maid, "/api/area-moves/" + given.id(), AreaMoveResponse.class).lines().getFirst().returnable()).isEqualByComparingTo("1");
        assertHealthy();
    }

    /** A report changes no figure; never more than was given less what's come back; a report says why. */
    @Test
    void aReportChangesNoFigureAndNothingComesBackTwice() {
        Session s = session("Return Report");
        stockIn(s, 10);
        Areas a = areas(s, "Housekeeping", false, false);
        Session maid = person(s, "maid", List.of(a.other()));
        AreaMoveResponse given = move(s, a.store(), a.other(), 3);

        AreaMoveResponse reported = giveBackOrReport(maid, given.id(),
                returnBody(UUID.randomUUID(), s, "REPORT", 2, "SPOILT", "Went mouldy"));

        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(branchFigure(s)).isEqualTo(7);
        assertThat(reported.lines()).singleElement().satisfies(line -> {
            assertThat(line.returns()).singleElement().satisfies(r -> {
                assertThat(r.kind()).isEqualTo("REPORT");
                assertThat(r.reason()).isEqualTo("SPOILT");
            });
            assertThat(line.returnable()).isEqualByComparingTo("1");
        });
        ResponseEntity<String> tooMany = post(maid, "/api/area-moves/" + given.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "GIVE_BACK", 2, null, null), String.class);
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooMany.getBody()).contains("Only 1 unit of the");
        ResponseEntity<String> noReason = post(maid, "/api/area-moves/" + given.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "REPORT", 1, null, null), String.class);
        assertThat(noReason.getBody()).contains("Say what happened to it");
        assertThat(notifications(s, "AREA_RETURN")).isEqualTo(1);
        assertHealthy();
    }

    /** Only its people, only within 7 days. */
    @Test
    void whoMayGiveBackAndWhen() {
        Session s = session("Return Who");
        stockIn(s, 10);
        Areas a = areas(s, "Housekeeping", false, false);
        UUID kitchen = addArea(s, "Kitchen", true, false);
        Session maid = person(s, "maid", List.of(a.other()));
        Session cook = person(s, "cook", List.of(kitchen));
        AreaMoveResponse given = move(s, a.store(), a.other(), 3);
        AreaMoveResponse toKitchen = move(s, a.store(), kitchen, 2);

        assertThat(post(cook, "/api/area-moves/" + given.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "GIVE_BACK", 1, null, null), String.class).getStatusCode())
                .as("the cook isn't in Housekeeping").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(given(cook)).singleElement().satisfies(move -> assertThat(move.id()).isEqualTo(toKitchen.id()));
        assertThat(post(maid, "/api/area-moves/" + toKitchen.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "GIVE_BACK", 1, null, null), String.class).getStatusCode())
                .as("the maid isn't in the Kitchen").isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE area_moves SET moved_at = now() - interval '8 days' WHERE id = :id",
                new MapSqlParameterSource("id", given.id()));
        assertThat(post(maid, "/api/area-moves/" + given.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "GIVE_BACK", 1, null, null), String.class).getBody())
                .contains("more than 7 days ago");
        assertThat(given(maid)).isEmpty();
    }

    /**
     * Confirming short says what happened (owner, 2026-11-01): 3 of 5 expired, with the cook's note - an Expired
     * stock-out at the Store carrying it, and the mover told the reason and the note. A phone from before sends
     * one reason for the move, and that still works.
     */
    @Test
    void aShortConfirmationSaysWhatHappenedWithANote() {
        Session s = session("Return Confirm");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, true);
        Session cook = person(s, "cook", List.of(a.other()));
        AreaMoveResponse moved = move(s, a.store(), a.other(), 5);

        AreaMoveResponse confirmed = confirm(cook, moved.id(), Map.of(
                "lines", List.of(Map.of("productId", s.productId().toString(), "got", 2, "rest", "EXPIRED")),
                "note", "The van was too hot"));

        assertThat(confirmed.confirmNote()).isEqualTo("The van was too hot");
        assertThat(confirmed.lines()).singleElement().satisfies(line -> {
            assertThat(line.restReason()).isEqualTo("EXPIRED");
            assertThat(line.damaged()).isEqualByComparingTo("3");
        });
        Map<String, Object> out = jdbc.queryForMap("""
                SELECT out_reason, area_id, quantity, note FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'
                """, new MapSqlParameterSource("p", s.productId()));
        assertThat(out).containsEntry("out_reason", "EXPIRED").containsEntry("area_id", a.store()).containsEntry("quantity", new java.math.BigDecimal("3.000"))
                .containsEntry("note", "Expired, on the way to Kitchen: The van was too hot");
        assertThat(jdbc.queryForObject("""
                SELECT body FROM notifications WHERE user_id = :u AND type = 'AREA_MOVE_SHORT'
                """, new MapSqlParameterSource("u", s.login().user().id()), String.class))
                .isEqualTo("3 expired · The van was too hot");
        assertThat(areaFigure(a.store(), s)).isEqualTo(5);
        assertThat(areaFigure(a.other(), s)).isEqualTo(2);

        AreaMoveResponse older = move(s, a.store(), a.other(), 2);
        AreaMoveResponse lost = confirm(cook, older.id(), Map.of("lines", List.of(line(s, 1)), "rest", "LOST"));
        assertThat(lost.lines()).singleElement().satisfies(line -> assertThat(line.restReason()).isEqualTo("LOST"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements WHERE product_id = :p AND out_reason = 'LOST' AND quantity = 1
                """, new MapSqlParameterSource("p", s.productId()), Long.class)).isEqualTo(1);
        assertHealthy();
    }

    /**
     * The Kitchen keeps its stock (owner, 2026-11-01: "the kitchen might accept the can and want to give it back on
     * inspection that it has expired"): a give back is a move back to the Store, linked to this one, saying why - not
     * offered to be given back itself; a report is a stock-out at the Kitchen; never more than the Kitchen has.
     */
    @Test
    void theKitchenGivesBackAnExpiredTinAndReportsASpoiltOne() {
        Session s = session("Return Kitchen");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        AreaMoveResponse moved = move(s, a.store(), a.other(), 4);

        UUID backId = UUID.randomUUID();
        AreaMoveResponse back = giveBackOrReport(cook, moved.id(), returnBody(backId, s, "GIVE_BACK", 1, "EXPIRED", "Dented and puffed"));

        assertThat(areaFigure(a.store(), s)).isEqualTo(7);
        assertThat(areaFigure(a.other(), s)).isEqualTo(3);
        assertThat(branchFigure(s)).isEqualTo(10);
        assertThat(back.lines().getFirst().returns()).singleElement().satisfies(r -> assertThat(r.reason()).isEqualTo("EXPIRED"));
        AreaMoveResponse moveBack = get(cook, "/api/area-moves/" + backId, AreaMoveResponse.class);
        assertThat(moveBack.fromAreaName()).isEqualTo("Kitchen");
        assertThat(moveBack.toAreaName()).isEqualTo("Store");
        assertThat(moveBack.note()).isEqualTo("Given back, expired: Dented and puffed");
        assertThat(moveBack.canReturn()).as("a give back isn't given back again").isFalse();
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE user_id = :u AND type = 'AREA_RETURN'",
                new MapSqlParameterSource("u", s.login().user().id()), String.class))
                .isEqualTo("The Kitchen gave back 1 unit of " + product(s).name() + ": expired");

        giveBackOrReport(cook, moved.id(), returnBody(UUID.randomUUID(), s, "REPORT", 1, "SPOILT", "Mould"));
        assertThat(areaFigure(a.other(), s)).isEqualTo(2);
        assertThat(branchFigure(s)).isEqualTo(9);
        Map<String, Object> out = jdbc.queryForMap(
                "SELECT out_reason, area_id, note FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", s.productId()));
        assertThat(out).containsEntry("out_reason", "DAMAGED").containsEntry("area_id", a.other())
                .containsEntry("note", "Spoilt in the Kitchen: Mould");

        // The Kitchen used the other 2: there's nothing there to give back, whatever the move said.
        ResponseEntity<String> used = post(s, "/api/products/" + s.productId() + "/stock/stock-out",
                Map.of("quantity", 2, "areaId", a.other().toString()), String.class);
        assertThat(used.getStatusCode()).as(used.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(post(cook, "/api/area-moves/" + moved.id() + "/returns",
                returnBody(UUID.randomUUID(), s, "GIVE_BACK", 1, null, null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertHealthy();
    }

    private static Map<String, Object> returnBody(UUID id, Session s, String kind, int quantity, String reason, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id.toString());
        body.put("productId", s.productId().toString());
        body.put("kind", kind);
        body.put("quantity", quantity);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private AreaMoveResponse giveBackOrReport(Session who, UUID moveId, Map<String, Object> body) {
        ResponseEntity<String> response = post(who, "/api/area-moves/" + moveId + "/returns", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return get(who, "/api/area-moves/" + moveId, AreaMoveResponse.class);
    }

    private List<AreaMoveResponse> given(Session who) {
        return restTemplate.exchange("/api/area-moves/received", HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<List<AreaMoveResponse>>() {}).getBody();
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
