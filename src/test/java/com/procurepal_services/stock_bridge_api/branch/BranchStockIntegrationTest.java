package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchHealthResponse;
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
 * Multiple branches, Phase 0 (MULTI_BRANCH_BUILD_PLAN.md section 8.1): every stock write lands at a
 * branch, a one-branch company behaves exactly as before, and the ledger is per branch underneath.
 *
 * <p>Phase 0 has no way to add a second branch, so the two-branch tests insert one with SQL. That is
 * what Phase 1's "Add a branch" will do, minus copying the existing figures into the main branch,
 * which these companies don't need: they record nothing before the second branch exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BranchStockIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private BranchHealthService branchHealthService;

    // ------------------------------------------------------------------ one branch: nothing changes

    @Test
    void aOneBranchCompanysBranchRowFollowsEveryWriteAndEveryMovementIsAtThatBranch() {
        Session s = session("Branch Mirror");
        UUID main = mainBranch(s);

        stockIn(s, body(20));
        assertMirror(s, main, 20);
        stockOut(s, body(5));
        assertMirror(s, main, 15);
        StockMutationResponse counted = write(s, "count", Map.of("countedQuantity", 12), StockMutationResponse.class).getBody();
        assertMirror(s, main, 12);
        voidWrite(s, counted.movement().id());
        assertMirror(s, main, 15);

        List<UUID> branches = jdbc.queryForList(
                "SELECT DISTINCT branch_id FROM stock_movements WHERE product_id = :p",
                new MapSqlParameterSource("p", s.productId()), UUID.class);
        assertThat(branches).containsExactly(main);
    }

    /** The ceiling a single shop is judged against is still the product's own figure. */
    @Test
    void aOneBranchCompanyIsStillRefusedOnlyBeyondItsProductFigure() {
        Session s = session("Branch Ceiling One");
        stockIn(s, body(10));

        ResponseEntity<InsufficientStockErrorResponse> refused =
                write(s, "stock-out", body(11), InsufficientStockErrorResponse.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().availableQuantity()).isEqualTo(10);
        assertThat(stockOut(s, body(10)).product().quantityOnHand()).isZero();
    }

    @Test
    void aWriteNamingTheCompanysOwnBranchIsRecordedThere() {
        Session s = session("Branch Named");
        UUID main = mainBranch(s);

        stockIn(s, at(body(7), main));

        assertMirror(s, main, 7);
    }

    // ---------------------------------------------------------------------------- refused branches

    @Test
    void aWriteNamingAnotherCompanysBranchIsRefusedAndChangesNothing() {
        Session mine = session("Branch Mine");
        Session theirs = session("Branch Theirs");
        stockIn(mine, body(5));

        ResponseEntity<String> refused = write(mine, "stock-in", at(body(3), mainBranch(theirs)), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).contains("That branch was not found.");
        assertThat(product(mine).quantityOnHand()).isEqualTo(5);
    }

    @Test
    void aWriteAtAClosedBranchIsRefused() {
        Session s = session("Branch Closed");
        UUID second = addBranch(s, "Closed Depot");
        jdbc.update("UPDATE branches SET is_active = FALSE WHERE id = :id", new MapSqlParameterSource("id", second));

        ResponseEntity<String> refused = write(s, "stock-in", at(body(3), second), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(product(s).quantityOnHand()).isZero();
    }

    // ----------------------------------------------------------------------- two branches (Phase 1)

    /** D-B10: an old copy of the app must not record at a guessed branch. */
    @Test
    void aWriteWithoutABranchIsRefusedOnceTheCompanyHasTwo() {
        Session s = session("Branch Required");
        addBranch(s, "Second Depot");

        ResponseEntity<String> refused = write(s, "stock-in", body(3), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Choose the branch this is for");
        assertThat(product(s).quantityOnHand()).isZero();
    }

    @Test
    void eachBranchHasItsOwnFigureAndTheProductCarriesTheirSum() {
        Session s = session("Branch Sum");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");

        stockIn(s, at(body(10), main));
        StockMutationResponse in = stockIn(s, at(body(4), second));

        assertThat(in.product().quantityOnHand()).isEqualTo(14);
        assertThat(branchFigure(main, s)).isEqualTo(10);
        assertThat(branchFigure(second, s)).isEqualTo(4);
    }

    /** "Oshodi has 4": a sale is judged against its own branch's shelf, not the company's. */
    @Test
    void aStockOutIsJudgedAgainstItsBranchAndDrawsOnlyOnThatBranchesLots() {
        Session s = session("Branch Lots");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");
        stockIn(s, at(body(10, hoursAgo(5)), main)); // the older lot, at the other branch
        StockMutationResponse secondLot = stockIn(s, at(body(4, hoursAgo(3)), second));

        ResponseEntity<InsufficientStockErrorResponse> refused =
                write(s, "stock-out", at(body(6), second), InsufficientStockErrorResponse.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().availableQuantity()).isEqualTo(4);

        StockMutationResponse sold = stockOut(s, at(body(3), second));
        assertThat(sold.breakdown()).singleElement().satisfies(draw -> {
            assertThat(draw.inMovementId()).isEqualTo(secondLot.movement().id());
            assertThat(draw.quantity()).isEqualTo(3);
        });
        assertThat(branchFigure(second, s)).isEqualTo(1);
        assertThat(branchFigure(main, s)).isEqualTo(10);
        assertThat(sold.product().quantityOnHand()).isEqualTo(11);
    }

    /**
     * A count is of one branch's shelf. A sale at the other branch after the count was taken is not
     * carried into it, and a count at the other branch doesn't supersede it.
     */
    @Test
    void countsAreCarriedForwardAndSupersededPerBranch() {
        Session s = session("Branch Counts");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");
        stockIn(s, at(body(10, hoursAgo(5)), main));
        stockIn(s, at(body(4, hoursAgo(5)), second));
        stockOut(s, at(body(2), second)); // now: after the count below was taken
        write(s, "count", Map.of("countedQuantity", 3, "branchId", second.toString()), StockMutationResponse.class);

        Map<String, Object> lateCount = new HashMap<>();
        lateCount.put("countedQuantity", 7);
        lateCount.put("countedAt", hoursAgo(1).toString());
        lateCount.put("branchId", main.toString());
        StockMutationResponse counted = write(s, "count", lateCount, StockMutationResponse.class).getBody();

        assertThat(counted.movement()).as("not superseded by the other branch's newer count").isNotNull();
        assertThat(branchFigure(main, s)).as("the other branch's sale is not carried in").isEqualTo(7);
        assertThat(branchFigure(second, s)).isEqualTo(3);
        assertThat(counted.product().quantityOnHand()).isEqualTo(10);
    }

    @Test
    void undoGivesTheStockBackToTheBranchItCameFrom() {
        Session s = session("Branch Undo");
        UUID main = mainBranch(s);
        UUID second = addBranch(s, "Oshodi");
        stockIn(s, at(body(10), main));
        stockIn(s, at(body(4), second));
        StockMutationResponse sold = stockOut(s, at(body(3), second));

        voidWrite(s, sold.movement().id());

        assertThat(branchFigure(second, s)).isEqualTo(4);
        assertThat(branchFigure(main, s)).isEqualTo(10);
        assertThat(product(s).quantityOnHand()).isEqualTo(14);
    }

    // ------------------------------------------------------------------------------- branch health

    @Test
    void branchHealthFindsNoMovementAtAForeignBranchAndNoMultiBranchTotalOutOfStep() {
        Session s = session("Branch Health");
        UUID second = addBranch(s, "Oshodi");
        stockIn(s, at(body(4), second));

        BranchHealthResponse health = branchHealthService.health();

        assertThat(health.movementsAtAnotherCompanysBranch()).isZero();
        assertThat(health.totalMismatchProducts()).isZero();
        assertThat(health.multiBranchCompanies()).isPositive();
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

    private int branchFigure(UUID branchId, Session s) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branchId).addValue("p", s.productId()),
                Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private void assertMirror(Session s, UUID main, int expected) {
        assertThat(product(s).quantityOnHand()).isEqualTo(expected);
        assertThat(branchFigure(main, s)).as("the one branch's row mirrors the product").isEqualTo(expected);
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
        HttpHeaders headers = auth(s);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/products/" + s.productId() + "/stock/" + action, HttpMethod.POST, new HttpEntity<>(body, headers), type);
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
                new CreateProductRequest("Product " + unique.substring(0, 6), "B-" + unique.substring(0, 8), null,
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
