package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
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
 * Multiple branches, Phase 1, server side (MULTI_BRANCH_BUILD_PLAN.md section 8.1): adding the second
 * branch and what it switches, who works where and what that lets them record, closing and the main
 * branch, and the selling-price permission (MULTI_BRANCH_UX_PLAN.md sections 5.1, 5.10, 5.11, 5.14).
 * The local profile runs the rollout "open"; the pilot gate has its own class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BranchManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    // ------------------------------------------------------------------------------ one branch

    @Test
    void aOneBranchCompanySeesOneBranchAndNoMultiBranchInterface() {
        TenantLoginResponse owner = signup("One Branch");

        BranchListResponse list = branches(owner);

        assertThat(list.multiBranch()).isFalse();
        assertThat(list.branches()).singleElement().satisfies(branch -> {
            assertThat(branch.main()).isTrue();
            assertThat(branch.canRecord()).isTrue();
        });
        assertThat(list.mayAddBranch()).isTrue();
    }

    // ------------------------------------------------------------------- the second branch (D-B13)

    @Test
    void theSecondBranchCopiesEveryFigureToTheMainBranchAndPlacesEverybodyThere() {
        TenantLoginResponse owner = signup("Second Branch");
        UUID product = product(owner, "Rice");
        stock(owner, product, "stock-in", Map.of("quantity", 12));
        UserSummaryResponse storekeeper = createUser(owner, "keeper", role("STOREKEEPER"), null);
        UUID main = branches(owner).branches().getFirst().id();
        // A figure set outside the ledger, as an old import did: the mirror is behind until a write.
        jdbc.update("UPDATE products SET quantity_on_hand = 15 WHERE id = :id", new MapSqlParameterSource("id", product));

        BranchResponse oshodi = addBranch(owner, "Oshodi").getBody();

        assertThat(oshodi.main()).isFalse();
        assertThat(branchFigure(main, product)).as("copied from the product, as it stood").isEqualTo(15);
        assertThat(user(owner, storekeeper.id()).branchIds()).containsExactly(main);
        assertThat(branches(owner).multiBranch()).isTrue();
    }

    /** D-B10: an owner works everywhere, so with two branches a write must say which. */
    @Test
    void withTwoBranchesAWriteWithoutABranchIsRefusedAndOneWithABranchLandsThere() {
        TenantLoginResponse owner = signup("Two Branch Writes");
        UUID product = product(owner, "Beans");
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();

        assertThat(stockRaw(owner, product, "stock-in", Map.of("quantity", 3)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        StockMutationResponse in = stock(owner, product, "stock-in", Map.of("quantity", 4, "branchId", oshodi.toString()));

        assertThat(in.product().quantityOnHand()).isEqualTo(4);
        assertThat(branchFigure(oshodi, product)).isEqualTo(4);
    }

    // ----------------------------------------------------------------------------- who works where

    @Test
    void aStorekeeperAtOneBranchRecordsThereWithoutSayingSoAndIsRefusedElsewhere() {
        TenantLoginResponse owner = signup("Keeper Branch");
        UUID product = product(owner, "Oil");
        UUID main = branches(owner).branches().getFirst().id();
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();
        createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));
        TenantLoginResponse ade = login(owner, "ade");

        StockMutationResponse in = stock(ade, product, "stock-in", Map.of("quantity", 5));
        ResponseEntity<String> elsewhere =
                stockRaw(ade, product, "stock-in", Map.of("quantity", 1, "branchId", main.toString()));

        assertThat(branchFigure(oshodi, product)).as("their one branch, without naming it").isEqualTo(5);
        assertThat(in.product().quantityOnHand()).isEqualTo(5);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(elsewhere.getBody()).contains("You don't work at Head Office");
        assertThat(branches(ade).branches()).extracting(BranchResponse::id).containsExactly(oshodi);
    }

    @Test
    void movingSomebodyToAnotherBranchTakesEffectOnTheirNextWrite() {
        TenantLoginResponse owner = signup("Keeper Moved");
        UUID product = product(owner, "Salt");
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();
        UUID bolade = addBranch(owner, "Bolade").getBody().id();
        UserSummaryResponse ade = createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));
        TenantLoginResponse adeLogin = login(owner, "ade");

        updateUser(owner, ade.id(), Map.of("branchIds", List.of(bolade.toString())));
        ResponseEntity<String> atOld =
                stockRaw(adeLogin, product, "stock-in", Map.of("quantity", 1, "branchId", oshodi.toString()));

        assertThat(atOld.getStatusCode()).as("the same access token, the branches as they are now")
                .isEqualTo(HttpStatus.FORBIDDEN);
        stock(adeLogin, product, "stock-in", Map.of("quantity", 2));
        assertThat(branchFigure(bolade, product)).isEqualTo(2);
    }

    @Test
    void aNewPersonAtAMultiBranchCompanyNeedsABranchUnlessTheirRoleWorksEverywhere() {
        TenantLoginResponse owner = signup("New Person");
        addBranch(owner, "Oshodi");

        ResponseEntity<String> nowhere = createUserRaw(owner, "nobody", role("STOREKEEPER"), null);
        ResponseEntity<String> owner2 = createUserRaw(owner, "second-owner", role("OWNER"), null);

        assertThat(nowhere.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(nowhere.getBody()).contains("Choose at least one branch");
        assertThat(owner2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users u JOIN clients c ON c.id = u.client_id "
                + "WHERE u.username = 'nobody' AND c.slug = :slug",
                new MapSqlParameterSource("slug", owner.user().clientIdentifier()), Long.class))
                .as("no half-made user").isZero();
    }

    // ----------------------------------------------------------------------- closing, main, names

    @Test
    void aBranchWithStockOrSomebodyWhoOnlyWorksThereCannotBeClosedUntilThatIsDealtWith() {
        TenantLoginResponse owner = signup("Close Branch");
        UUID product = product(owner, "Sugar");
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();
        UserSummaryResponse ade = createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));
        stock(owner, product, "stock-in", Map.of("quantity", 3, "branchId", oshodi.toString()));

        ResponseEntity<String> withStock = post(owner, "/api/branches/" + oshodi + "/close", null, String.class);
        assertThat(withStock.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(withStock.getBody()).contains("Oshodi still has 1 product in stock");

        stock(owner, product, "count", Map.of("countedQuantity", 0, "branchId", oshodi.toString()));
        ResponseEntity<String> withPerson = post(owner, "/api/branches/" + oshodi + "/close", null, String.class);
        assertThat(withPerson.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(withPerson.getBody()).contains("only works at Oshodi");

        updateUser(owner, ade.id(), Map.of("branchIds", List.of(branches(owner).branches().getFirst().id().toString())));
        BranchResponse closed = post(owner, "/api/branches/" + oshodi + "/close", null, BranchResponse.class).getBody();
        assertThat(closed.active()).isFalse();
        assertThat(stockRaw(owner, product, "stock-in", Map.of("quantity", 1, "branchId", oshodi.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theMainBranchCanMoveButNeverBeClosed() {
        TenantLoginResponse owner = signup("Main Branch");
        UUID headOffice = branches(owner).branches().getFirst().id();
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();

        assertThat(post(owner, "/api/branches/" + headOffice + "/close", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        BranchResponse moved = post(owner, "/api/branches/" + oshodi + "/main", null, BranchResponse.class).getBody();

        assertThat(moved.main()).isTrue();
        assertThat(branches(owner).branches()).filteredOn(BranchResponse::main).extracting(BranchResponse::id)
                .containsExactly(oshodi);
    }

    @Test
    void branchNamesAreUniqueWhateverTheirCase() {
        TenantLoginResponse owner = signup("Branch Names");
        addBranch(owner, "Oshodi");

        ResponseEntity<String> again = post(owner, "/api/branches", Map.of("name", "oshodi"), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("There's already a branch called oshodi");
    }

    // ------------------------------------------------------------------------- selling prices (5.14)

    @Test
    void withoutChangeSellingPricesAPriceCannotChangeButEverythingElseCan() {
        TenantLoginResponse owner = signup("Price Rights");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        UUID product = product(owner, "Milk");
        updateProduct(owner, product, Map.of("unitPrice", 500));
        UUID clerk = createRole(owner, "Catalogue Clerk", Set.of("VIEW_PRODUCTS", "MANAGE_PRODUCTS"));
        createUser(owner, "clerk", clerk, null);
        TenantLoginResponse clerkLogin = login(owner, "clerk");

        ResponseEntity<String> newPrice = updateProductRaw(clerkLogin, product, Map.of("unitPrice", 650));
        ResponseEntity<String> renamed = updateProductRaw(clerkLogin, product, Map.of("name", "Milk 1L", "unitPrice", 500));

        assertThat(newPrice.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(newPrice.getBody()).contains("Prices are set by people who can change selling prices");
        assertThat(renamed.getStatusCode()).as("the current price sent back is not a change").isEqualTo(HttpStatus.OK);
        ProductResponse after = productOf(owner, product);
        assertThat(after.name()).isEqualTo("Milk 1L");
        assertThat(after.unitPrice()).isEqualByComparingTo("500");
    }

    // ------------------------------------------------------------------------------------ helpers

    private BranchListResponse branches(TenantLoginResponse who) {
        return restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(who)), BranchListResponse.class)
                .getBody();
    }

    private ResponseEntity<BranchResponse> addBranch(TenantLoginResponse owner, String name) {
        ResponseEntity<BranchResponse> response = post(owner, "/api/branches", Map.of("name", name), BranchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private int branchFigure(UUID branchId, UUID productId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branchId).addValue("p", productId), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private StockMutationResponse stock(TenantLoginResponse who, UUID product, String action, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = post(
                who, "/api/products/" + product + "/stock/" + action, body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).as("%s %s", action, body).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> stockRaw(TenantLoginResponse who, UUID product, String action, Map<String, Object> body) {
        return post(who, "/api/products/" + product + "/stock/" + action, body, String.class);
    }

    private UUID role(String name) {
        return roleRepository.findByName(name).orElseThrow().getId();
    }

    private UUID createRole(TenantLoginResponse owner, String name, Set<String> permissions) {
        return post(owner, "/api/roles",
                Map.of("name", name, "description", "Test role", "permissionCodes", permissions), RoleResponse.class)
                .getBody().id();
    }

    private UserSummaryResponse createUser(TenantLoginResponse owner, String username, UUID roleId, List<UUID> branchIds) {
        ResponseEntity<UserSummaryResponse> response =
                post(owner, "/api/users", userBody(username, roleId, branchIds), UserSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> createUserRaw(TenantLoginResponse owner, String username, UUID roleId, List<UUID> branchIds) {
        return post(owner, "/api/users", userBody(username, roleId, branchIds), String.class);
    }

    private static Map<String, Object> userBody(String username, UUID roleId, List<UUID> branchIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleId.toString());
        if (branchIds != null) {
            body.put("branchIds", branchIds.stream().map(UUID::toString).toList());
        }
        return body;
    }

    private UserSummaryResponse user(TenantLoginResponse owner, UUID userId) {
        return restTemplate.exchange("/api/users/" + userId, HttpMethod.GET, new HttpEntity<>(auth(owner)),
                UserSummaryResponse.class).getBody();
    }

    private void updateUser(TenantLoginResponse owner, UUID userId, Map<String, Object> body) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.PUT, new HttpEntity<>(body, json(owner)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private TenantLoginResponse login(TenantLoginResponse owner, String username) {
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    private UUID product(TenantLoginResponse owner, String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name + " " + unique, "BM-" + unique, null, null, null, null, null, null, null),
                jsonOnly()));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private ProductResponse productOf(TenantLoginResponse who, UUID product) {
        return restTemplate.exchange("/api/products/" + product, HttpMethod.GET, new HttpEntity<>(auth(who)),
                ProductResponse.class).getBody();
    }

    private void updateProduct(TenantLoginResponse who, UUID product, Map<String, Object> patch) {
        ResponseEntity<String> response = updateProductRaw(who, product, patch);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> updateProductRaw(TenantLoginResponse who, UUID product, Map<String, Object> patch) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("product", new HttpEntity<>(patch, jsonOnly()));
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products/" + product, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private <T> ResponseEntity<T> post(TenantLoginResponse who, String path, Object body, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(who)), type);
    }

    private void put(TenantLoginResponse who, String path, Object body) {
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, json(who)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com",
                        PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders jsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders json(TenantLoginResponse who) {
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders auth(TenantLoginResponse who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(who.tokens().accessToken());
        return headers;
    }
}
