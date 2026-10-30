package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.sync.ProductSnapshotPage;
import com.procurepal_services.stock_bridge_api.product.sync.ProductSyncRow;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Areas, Part 1 (STOCK_AREAS_PART_1.md): adding areas to a branch, the rules for changing them, the pilot
 * gate and who may, and the branch list, product page, product list and phone rows carrying them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AreaManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AreaHealthService areaHealthService;

    @Autowired
    private AreaService areaService;

    @Autowired
    private RoleRepository roleRepository;

    // ------------------------------------------------------------------------------- adding areas

    /** "Everything you have is in the Store": the switch copies the branch's figures into its first area. */
    @Test
    void addingAreasToABranchThatHoldsStockPutsAllOfItInTheStore() {
        Session s = session("Areas Switch");
        UUID main = mainBranch(s);
        stockIn(s, body(10));

        ResponseEntity<List<AreaResponse>> added = addAreas(s, main,
                newArea("Kitchen", true), newArea("Bar", true), newArea("Housekeeping", false));

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody()).extracting(AreaResponse::name).containsExactly("Kitchen", "Bar", "Housekeeping");
        UUID store = areaId(s, main, "Store");
        assertThat(areaFigure(store, s)).isEqualTo(10);
        AreaHealthResponse health = areaHealthService.health();
        assertThat(health.healthy()).as(health.toString()).isTrue();

        BranchResponse branch = branches(s).branches().getFirst();
        assertThat(branch.areas()).extracting(AreaResponse::name).containsExactly("Store", "Kitchen", "Bar", "Housekeeping");
        AreaResponse storeArea = branch.areas().getFirst();
        assertThat(storeArea.delivery()).isTrue();
        assertThat(storeArea.productsInStock()).isEqualTo(1);
        assertThat(branch.areas().getLast().keepsStock()).isFalse();
        assertThat(branch.areas().getLast().productsInStock()).as("a used area holds nothing to count").isNull();
        assertThat(branches(s).mayAddArea()).isTrue();

        // The product page: every area that keeps stock, the used one left out.
        assertThat(figures(product(s).areaStock())).containsExactly(
                Map.entry(store, 10), Map.entry(areaId(s, main, "Kitchen"), 0), Map.entry(areaId(s, main, "Bar"), 0));
    }

    @Test
    void writesAfterTheSwitchLandInTheirAreaAndEveryReaderCarriesTheFigures() {
        Session s = session("Areas Readers");
        UUID main = mainBranch(s);
        stockIn(s, body(10));
        addAreas(s, main, newArea("Kitchen", true));
        UUID store = areaId(s, main, "Store");
        UUID kitchen = areaId(s, main, "Kitchen");

        stockIn(s, in(body(3), kitchen));

        assertThat(figures(product(s).areaStock())).containsEntry(store, 10).containsEntry(kitchen, 3);
        List<ProductResponse> list = restTemplate.exchange("/api/products?size=20", HttpMethod.GET,
                new HttpEntity<>(auth(s)), new ParameterizedTypeReference<PageOf<ProductResponse>>() {}).getBody().content();
        assertThat(figures(list.getFirst().areaStock())).containsEntry(kitchen, 3);
        ProductSnapshotPage snapshot = restTemplate.exchange("/api/products/sync/snapshot?limit=50", HttpMethod.GET,
                new HttpEntity<>(auth(s)), ProductSnapshotPage.class).getBody();
        ProductSyncRow row = snapshot.products().stream().filter(r -> r.id().equals(s.productId())).findFirst().orElseThrow();
        assertThat(figures(row.areaStock())).containsEntry(store, 10).containsEntry(kitchen, 3);
    }

    @Test
    void aBranchWithOneAreaCarriesNoAreaFigures() {
        Session s = session("Areas None");
        stockIn(s, body(4));

        assertThat(product(s).areaStock()).isNull();
        assertThat(branches(s).branches().getFirst().areas()).singleElement()
                .satisfies(area -> assertThat(area.productsInStock()).isNull());
    }

    @Test
    void areaNamesAreUniqueInABranchWhateverTheirCase() {
        Session s = session("Areas Names");
        UUID main = mainBranch(s);
        addAreas(s, main, newArea("Kitchen", true));

        ResponseEntity<String> again = addAreasRaw(s, main, newArea(" kitchen ", true));
        ResponseEntity<String> blank = addAreasRaw(s, main, newArea("  ", true));
        ResponseEntity<String> twice = addAreasRaw(s, main, newArea("Bar", true), newArea("BAR", true));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("There's already an area called kitchen here.");
        assertThat(blank.getBody()).contains("Give the area a name.");
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(branches(s).branches().getFirst().areas()).hasSize(2);
    }

    // ---------------------------------------------------------------------------- changing areas

    @Test
    void anAreaHoldingStockCantCloseOrStopKeepingStockAndTheDeliveryAreaCantClose() {
        Session s = session("Areas Rules");
        UUID main = mainBranch(s);
        addAreas(s, main, newArea("Kitchen", true), newArea("Bar", true));
        UUID store = areaId(s, main, "Store");
        UUID kitchen = areaId(s, main, "Kitchen");
        UUID bar = areaId(s, main, "Bar");
        stockIn(s, in(body(2), kitchen));

        ResponseEntity<String> closeKitchen = post(s, "/api/areas/" + kitchen + "/close", null, String.class);
        ResponseEntity<String> usedKitchen = patch(s, kitchen, Map.of("keepsStock", false));
        ResponseEntity<String> closeStore = post(s, "/api/areas/" + store + "/close", null, String.class);
        ResponseEntity<String> usedStore = patch(s, store, Map.of("keepsStock", false));

        assertThat(closeKitchen.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(closeKitchen.getBody()).contains("Kitchen still has 1 item. Count it to zero first, then close it.");
        assertThat(usedKitchen.getBody()).contains("Kitchen still has 1 item.");
        assertThat(closeStore.getBody()).contains("Deliveries arrive in Store. Choose another area for deliveries");
        assertThat(usedStore.getBody()).contains("Deliveries arrive in Store, so it keeps stock.");

        // An empty area closes, refuses writes while closed, and reopens.
        assertThat(post(s, "/api/areas/" + bar + "/close", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> atClosed = write(s, "stock-in", in(body(1), bar), String.class);
        assertThat(atClosed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(atClosed.getBody()).contains("Bar is closed.");
        assertThat(post(s, "/api/areas/" + bar + "/reopen", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(write(s, "stock-in", in(body(1), bar), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deliveriesArriveWhereverTheOwnerSaysAndAnAreaCanBeRenamed() {
        Session s = session("Areas Delivery");
        UUID main = mainBranch(s);
        addAreas(s, main, newArea("Kitchen", true), newArea("Housekeeping", false));
        UUID store = areaId(s, main, "Store");
        UUID kitchen = areaId(s, main, "Kitchen");
        UUID housekeeping = areaId(s, main, "Housekeeping");

        ResponseEntity<String> toUsed = patch(s, housekeeping, Map.of("delivery", true));
        ResponseEntity<String> toKitchen = patch(s, kitchen, Map.of("delivery", true, "name", "Main kitchen"));
        stockIn(s, body(5));

        assertThat(toUsed.getBody()).contains("Housekeeping uses what it's given, so deliveries can't arrive there.");
        assertThat(toKitchen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(areaFigure(kitchen, s)).isEqualTo(5);
        assertThat(areaFigure(store, s)).isZero();
        List<AreaResponse> areas = branches(s).branches().getFirst().areas();
        assertThat(areas).filteredOn(AreaResponse::delivery).extracting(AreaResponse::name).containsExactly("Main kitchen");
        // The Store is no longer the delivery area, and it is empty, so it can stop keeping stock - but it
        // holds the branch's history from before areas, so it keeps it.
        assertThat(patch(s, store, Map.of("keepsStock", false)).getBody()).contains("holds this branch's stock history");
    }

    // ---------------------------------------------------------------------------- who may, and when

    @Test
    void inThePilotOnlyACompanySupportEnabledMayAddAreas() {
        Object rollout = ReflectionTestUtils.getField(areaService, "rollout");
        ReflectionTestUtils.setField(areaService, "rollout", "pilot");
        try {
            Session s = session("Areas Pilot");
            UUID main = mainBranch(s);
            stockIn(s, body(3));

            assertThat(branches(s).mayAddArea()).isFalse();
            ResponseEntity<String> refused = addAreasRaw(s, main, newArea("Kitchen", true));
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody()).contains("Areas aren't switched on for your company yet");
            assertThat(branches(s).branches().getFirst().areas()).as("not left half-switched").hasSize(1);

            jdbc.update("UPDATE clients SET areas_enabled = TRUE WHERE slug = :slug",
                    new MapSqlParameterSource("slug", s.login().user().clientIdentifier()));
            assertThat(branches(s).mayAddArea()).isTrue();
            assertThat(addAreasRaw(s, main, newArea("Kitchen", true)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            ReflectionTestUtils.setField(areaService, "rollout", rollout);
        }
    }

    @Test
    void onlySomeoneWhoManagesBranchesMayChangeAreasAndOnlyTheirOwnCompanys() {
        Session s = session("Areas Access");
        Session other = session("Areas Elsewhere");
        UUID main = mainBranch(s);
        TenantLoginResponse storekeeper = storekeeper(s, "kunle");

        HttpHeaders headers = bearer(storekeeper.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> byStorekeeper = restTemplate.exchange("/api/branches/" + main + "/areas", HttpMethod.POST,
                new HttpEntity<>(Map.of("areas", List.of(newArea("Kitchen", true))), headers), String.class);
        ResponseEntity<String> theirBranch = addAreasRaw(other, main, newArea("Kitchen", true));
        ResponseEntity<String> theirArea = patch(other, areaId(s, main, "Store"), Map.of("name", "Mine"));

        assertThat(byStorekeeper.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(theirBranch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirArea.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(branches(s).branches().getFirst().areas()).singleElement()
                .satisfies(area -> assertThat(area.name()).isEqualTo("Store"));
    }

    // ------------------------------------------------------------------------------------ helpers

    private record Session(TenantLoginResponse login, UUID productId) {
    }

    /** Just enough of Spring's page for the product list. */
    private record PageOf<T>(List<T> content) {
    }

    private static Map<String, Object> newArea(String name, boolean keepsStock) {
        Map<String, Object> area = new HashMap<>();
        area.put("name", name);
        area.put("keepsStock", keepsStock);
        return area;
    }

    private static Map<UUID, Integer> figures(List<AreaFigure> figures) {
        assertThat(figures).isNotNull();
        return figures.stream().collect(Collectors.toMap(AreaFigure::areaId, AreaFigure::quantityOnHand,
                (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private static Map<String, Object> body(int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
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

    private UUID areaId(Session s, UUID branchId, String name) {
        return jdbc.queryForObject("SELECT id FROM areas WHERE branch_id = :b AND name = :name",
                new MapSqlParameterSource().addValue("b", branchId).addValue("name", name), UUID.class);
    }

    private int areaFigure(UUID areaId, Session s) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM area_stock WHERE area_id = :a AND product_id = :p",
                new MapSqlParameterSource().addValue("a", areaId).addValue("p", s.productId()), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    @SafeVarargs
    private ResponseEntity<List<AreaResponse>> addAreas(Session s, UUID branchId, Map<String, Object>... areas) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<List<AreaResponse>> response = restTemplate.exchange("/api/branches/" + branchId + "/areas",
                HttpMethod.POST, new HttpEntity<>(Map.of("areas", List.of(areas)), headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    @SafeVarargs
    private ResponseEntity<String> addAreasRaw(Session s, UUID branchId, Map<String, Object>... areas) {
        return post(s, "/api/branches/" + branchId + "/areas", Map.of("areas", List.of(areas)), String.class);
    }

    private ResponseEntity<String> patch(Session s, UUID areaId, Map<String, Object> body) {
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/areas/" + areaId, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private BranchListResponse branches(Session s) {
        return restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(s)), BranchListResponse.class)
                .getBody();
    }

    private void stockIn(Session s, Map<String, Object> body) {
        ResponseEntity<String> response = write(s, "stock-in", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
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
                new CreateProductRequest("Product " + unique.substring(0, 6), "M-" + unique.substring(0, 8), null,
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
