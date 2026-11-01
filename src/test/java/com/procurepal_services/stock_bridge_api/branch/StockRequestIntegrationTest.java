package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.NoneInStoreLine;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.push.CapturingPushTransport;
import com.procurepal_services.stock_bridge_api.push.PushTestDevice;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Areas, Part 5 (STOCK_AREAS_PART_5.md): one area asking another for stock - asked, resent, filled whole and in
 * part, re-opened by Undo, none in the store and the buyer's list, cancelled, and who may do each.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockRequestIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CapturingPushTransport pushes;

    /** The push services, kept in memory: what would have reached each phone. */
    @TestConfiguration
    static class Pushes {
        @Bean
        @Primary
        CapturingPushTransport capturingPushTransport() {
            return new CapturingPushTransport();
        }
    }

    /** The cook asks; the storekeeper is told and moves 3 of 4, then the last 1; the cook hears each time. */
    @Test
    void theKitchenAsksAndTheStoreFillsItInPartThenInFull() {
        Session s = session("Request Fill");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        Session keeper = person(s, "keeper", List.of(a.store()));
        UUID id = UUID.randomUUID();

        StockRequestResponse asked = ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 4)));
        // Sent again (a lost reply): recorded once.
        ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 4)));

        assertThat(asked.status()).isEqualTo("OPEN");
        assertThat(asked.toAreaName()).isEqualTo("Kitchen");
        assertThat(asked.askedByName()).isEqualTo("cook");
        assertThat(asked.canCancel()).isTrue();
        assertThat(asked.canFill()).as("the cook doesn't work in the Store").isFalse();
        assertThat(count("SELECT COUNT(*) FROM stock_requests WHERE id = :id", id)).isEqualTo(1);
        assertThat(notifications(keeper, "STOCK_REQUEST")).isEqualTo(1);
        assertThat(notifications(cook, "STOCK_REQUEST")).isZero();
        assertThat(areaFigure(a.store(), s)).as("asking moves nothing").isEqualTo(10);
        assertThat(waiting(keeper)).singleElement().satisfies(r -> assertThat(r.canFill()).isTrue());
        assertThat(waiting(cook)).isEmpty();

        fill(keeper, id, a.store(), a.other(), Map.of(s.productId(), 3));
        StockRequestResponse part = request(cook, id);
        assertThat(part.status()).isEqualTo("OPEN");
        assertThat(part.lines()).singleElement().satisfies(line -> assertThat(line.moved()).isEqualTo(3));
        assertThat(part.canCancel()).as("some of it has moved").isFalse();
        assertThat(areaFigure(a.other(), s)).isEqualTo(3);
        assertThat(notifications(cook, "STOCK_REQUEST_ANSWERED")).isEqualTo(1);

        fill(keeper, id, a.store(), a.other(), Map.of(s.productId(), 1));
        assertThat(request(cook, id).status()).isEqualTo("DONE");
        assertThat(notifications(cook, "STOCK_REQUEST_ANSWERED")).isEqualTo(2);
        assertThat(waiting(keeper)).isEmpty();
        assertThat(post(cook, "/api/stock-requests/" + id + "/cancel", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertHealthy();
    }

    /**
     * "Tell Amaka on WhatsApp" (owner, 2026-11-01): the asker sees who can move it - the Store's people who can record
     * a stock-out and have a number - and nobody else sees those numbers.
     */
    @Test
    void theAskerSeesWhoCanMoveItWithTheirNumbersAndOnlyThey() {
        Session s = session("Request Movers");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        Session amaka = person(s, "amaka", List.of(a.store()));
        person(s, "nophone", List.of(a.store()));
        jdbc.update("UPDATE users SET phone = '0803 123 4567', first_name = 'Amaka' WHERE id = :id",
                new MapSqlParameterSource("id", amaka.login().user().id()));
        UUID id = UUID.randomUUID();

        StockRequestResponse asked = ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 2)));

        assertThat(asked.movers()).singleElement().satisfies(mover -> {
            assertThat(mover.name()).isEqualTo("Amaka");
            assertThat(mover.whatsapp()).isEqualTo("+2348031234567");
        });
        assertThat(request(s, id).movers()).as("the owner didn't ask").isEmpty();
        assertThat(request(amaka, id).movers()).isEmpty();
    }

    /**
     * Notifications (owner, 2026-11-01: "you get a notification … and if it has been moved it goes away"): the
     * Store's phone is told when the Kitchen asks - readable by that phone alone, signed by us - and, once it's moved,
     * gets a quiet one in its place. The asker's phone hears nothing; a phone the push service says is gone is dropped.
     */
    @Test
    void theStoresPhoneIsToldAndTheNotificationGoesOnceItsMoved() throws Exception {
        Session s = session("Request Push");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        Session keeper = person(s, "keeper", List.of(a.store()));
        Session other = person(s, "other", List.of(a.store()));
        PushTestDevice keepersPhone = new PushTestDevice();
        PushTestDevice cooksPhone = new PushTestDevice();
        Map<String, Object> gone = new HashMap<>(new PushTestDevice().subscription());
        gone.put("endpoint", "https://push.example.test/gone/" + UUID.randomUUID());
        assertThat(post(keeper, "/api/push/subscriptions", keepersPhone.subscription(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        post(cook, "/api/push/subscriptions", cooksPhone.subscription(), String.class);
        post(other, "/api/push/subscriptions", gone, String.class);
        UUID id = UUID.randomUUID();

        ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 2)));

        CapturingPushTransport.Sent asked = pushes.to(keepersPhone.endpoint(), 1).getFirst();
        JsonNode message = JsonMapper.builder().build().readTree(keepersPhone.read(asked.body()));
        assertThat(message.get("title").asString()).isEqualTo("The Kitchen asked for 1 item");
        assertThat(message.get("body").asString()).startsWith("Product ").contains("cook at ");
        assertThat(message.get("tag").asString()).isEqualTo("request-" + id);
        assertThat(message.get("url").asString()).isEqualTo("/app?request=" + id);
        assertThat(message.get("quiet").asBoolean()).isFalse();
        assertThat(asked.headers()).containsEntry("Content-Encoding", "aes128gcm").containsEntry("Urgency", "high")
                .containsEntry("Topic", id.toString().replace("-", ""));
        Claims claims = Jwts.parser().verifyWith(vapidKey(keeper)).build()
                .parseSignedClaims(asked.headers().get("Authorization").replaceFirst("^vapid t=([^,]+),.*$", "$1"))
                .getPayload();
        assertThat(claims.getAudience()).containsExactly("https://push.example.test");
        assertThat(claims.getSubject()).startsWith("mailto:");
        assertThat(pushes.to(cooksPhone.endpoint(), 0)).as("the asker isn't told of their own").isEmpty();
        pushes.to((String) gone.get("endpoint"), 1);
        assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = :id", other.login().user().id()))
                .as("the push service said that phone is gone").isZero();

        fill(keeper, id, a.store(), a.other(), Map.of(s.productId(), 2));

        CapturingPushTransport.Sent moved = pushes.to(keepersPhone.endpoint(), 2).get(1);
        JsonNode done = JsonMapper.builder().build().readTree(keepersPhone.read(moved.body()));
        assertThat(done.get("tag").asString()).as("in the first one's place").isEqualTo("request-" + id);
        assertThat(done.get("quiet").asBoolean()).isTrue();
        assertThat(done.get("body").asString()).isEqualTo("Moved by keeper");

        // Turned off on that phone: nothing more reaches it.
        HttpHeaders headers = auth(keeper);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange("/api/push/subscriptions", HttpMethod.DELETE,
                new HttpEntity<>(Map.of("endpoint", keepersPhone.endpoint()), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = :id", keeper.login().user().id())).isZero();
    }

    /** Undoing the move that filled it asks for it again. */
    @Test
    void undoingTheFillingMoveReopensTheRequest() {
        Session s = session("Request Undo");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        UUID id = UUID.randomUUID();
        ask(s, id, a.store(), a.other(), List.of(askLine(s.productId(), 2)));
        AreaMoveResponse moved = fill(s, id, a.store(), a.other(), Map.of(s.productId(), 2));
        assertThat(request(s, id).status()).isEqualTo("DONE");

        post(s, "/api/area-moves/" + moved.id() + "/undo", null, AreaMoveResponse.class);

        StockRequestResponse again = request(s, id);
        assertThat(again.status()).isEqualTo("OPEN");
        assertThat(again.lines()).singleElement().satisfies(line -> assertThat(line.moved()).isZero());
    }

    /** None in the store for one line, the other moved: done, and the buyer sees the missing one. */
    @Test
    void noneInTheStoreAnswersALineAndReachesTheBuyer() {
        Session s = session("Request None");
        stockIn(s, 10);
        UUID paste = createProduct(s, "Tomato paste");
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        Session keeper = person(s, "keeper", List.of(a.store()));
        UUID id = UUID.randomUUID();
        ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 2), askLine(paste, 3)));

        ResponseEntity<String> byCook = post(cook, "/api/stock-requests/" + id + "/none-in-store",
                Map.of("productIds", List.of(paste.toString())), String.class);
        assertThat(byCook.getStatusCode()).as("only the Store says what it has").isEqualTo(HttpStatus.FORBIDDEN);
        StockRequestResponse answered = post(keeper, "/api/stock-requests/" + id + "/none-in-store",
                Map.of("productIds", List.of(paste.toString())), StockRequestResponse.class).getBody();

        assertThat(answered.status()).isEqualTo("OPEN");
        assertThat(answered.lines()).filteredOn(line -> line.productId().equals(paste))
                .singleElement().satisfies(line -> assertThat(line.noneInStore()).isTrue());
        assertThat(notifications(cook, "STOCK_REQUEST_ANSWERED")).isEqualTo(1);
        assertThat(waiting(keeper)).as("rice is still to move").hasSize(1);

        fill(keeper, id, a.store(), a.other(), Map.of(s.productId(), 2));

        assertThat(request(cook, id).status()).isEqualTo("DONE");
        assertThat(waiting(keeper)).isEmpty();
        assertThat(noneInStore(s)).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(paste);
            assertThat(line.quantity()).isEqualTo(3);
            assertThat(line.toAreaName()).isEqualTo("Kitchen");
            assertThat(line.fromAreaName()).isEqualTo("Store");
            assertThat(line.askedByName()).isEqualTo("cook");
        });
    }

    /** Cancelled before anything moved; a move naming it later still moves, and isn't counted towards it. */
    @Test
    void aCancelledRequestIsntFilledButTheMoveStillHappens() {
        Session s = session("Request Cancel");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        Session cook = person(s, "cook", List.of(a.other()));
        UUID id = UUID.randomUUID();
        ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 2)));

        StockRequestResponse cancelled = post(cook, "/api/stock-requests/" + id + "/cancel", null,
                StockRequestResponse.class).getBody();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        fill(s, id, a.store(), a.other(), Map.of(s.productId(), 2));
        assertThat(areaFigure(a.other(), s)).isEqualTo(2);
        assertThat(request(s, id).lines()).singleElement().satisfies(line -> assertThat(line.moved()).isZero());
        assertThat(count("SELECT COUNT(*) FROM area_moves WHERE request_id = :id", id)).isZero();
    }

    /** Asking for an area you don't work in, asking an area that keeps no stock, and filling from the wrong area. */
    @Test
    void whoMayAskAndFill() {
        Session s = session("Request Who");
        stockIn(s, 10);
        Areas a = areas(s, "Kitchen", true, false);
        UUID bar = addArea(s, "Bar", true, false);
        UUID housekeeping = addArea(s, "Housekeeping", false, false);
        Session cook = person(s, "cook", List.of(a.other()));
        Session barman = person(s, "barman", List.of(bar));

        assertThat(raw(cook, a.store(), bar).getStatusCode()).as("for the Bar, which the cook isn't in")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(cook, housekeeping, a.other()).getBody()).contains("Housekeeping uses what it's given");
        assertThat(raw(cook, a.other(), a.other()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        UUID id = UUID.randomUUID();
        ask(cook, id, a.store(), a.other(), List.of(askLine(s.productId(), 2)));
        assertThat(waiting(barman)).isEmpty();
        assertThat(post(barman, "/api/stock-requests/" + id + "/cancel", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
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

    private StockRequestResponse ask(Session who, UUID id, UUID from, UUID to, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id.toString());
        body.put("fromAreaId", from.toString());
        body.put("toAreaId", to.toString());
        body.put("lines", lines);
        ResponseEntity<String> response = post(who, "/api/stock-requests", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return get(who, "/api/stock-requests/" + id, StockRequestResponse.class);
    }

    private static Map<String, Object> askLine(UUID productId, int quantity) {
        return Map.of("productId", productId.toString(), "quantity", quantity);
    }

    /** A move from {@code from} to {@code to} filling {@code requestId}, one line per product. */
    private AreaMoveResponse fill(Session who, UUID requestId, UUID from, UUID to, Map<UUID, Integer> quantities) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("fromAreaId", from.toString());
        body.put("toAreaId", to.toString());
        body.put("requestId", requestId.toString());
        body.put("lines", quantities.entrySet().stream()
                .map(e -> Map.of("productId", e.getKey().toString(), "quantity", e.getValue())).toList());
        ResponseEntity<AreaMoveResponse> response = post(who, "/api/area-moves", body, AreaMoveResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private StockRequestResponse request(Session who, UUID id) {
        return get(who, "/api/stock-requests/" + id, StockRequestResponse.class);
    }

    private List<StockRequestResponse> waiting(Session who) {
        return restTemplate.exchange("/api/stock-requests/waiting", HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<List<StockRequestResponse>>() {}).getBody();
    }

    private List<NoneInStoreLine> noneInStore(Session who) {
        return restTemplate.exchange("/api/stock-requests/none-in-store", HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<List<NoneInStoreLine>>() {}).getBody();
    }

    private UUID createProduct(Session s, String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name + " " + unique, "R-" + unique, null, new BigDecimal("9.99"), null, null, null,
                        null, null),
                part));
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private ResponseEntity<String> raw(Session who, UUID from, UUID to) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("fromAreaId", from.toString());
        body.put("toAreaId", to.toString());
        body.put("lines", List.of(Map.of("productId", who.productId().toString(), "quantity", 1)));
        return post(who, "/api/stock-requests", body, String.class);
    }

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), Long.class);
    }

    private long notifications(Session who, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = :u AND type = :t",
                new MapSqlParameterSource().addValue("u", who.login().user().id()).addValue("t", type), Long.class);
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
        return areaFigure(areaId, s.productId());
    }

    private int areaFigure(UUID areaId, UUID productId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM area_stock WHERE area_id = :a AND product_id = :p",
                new MapSqlParameterSource().addValue("a", areaId).addValue("p", productId), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
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

    private <T> T get(Session s, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(s)), type).getBody();
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

    /** The server's public key, as browsers get it, back as a key to check its signature with. */
    @SuppressWarnings("unchecked")
    private PublicKey vapidKey(Session s) throws Exception {
        byte[] raw = java.util.Base64.getUrlDecoder().decode((String) get(s, "/api/push/key", Map.class).get("publicKey"));
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec params =
                ((java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        ECPoint point = new ECPoint(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw, 1, 33)),
                new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params));
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
