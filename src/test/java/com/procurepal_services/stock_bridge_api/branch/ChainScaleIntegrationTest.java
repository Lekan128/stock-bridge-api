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
import java.util.ArrayList;
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
 * Multiple branches, Phase 4 (MULTI_BRANCH_BUILD_PLAN.md section 8.1 row 4): the products list per
 * branch and its stock filters in All branches, a chain's sync carrying one branch, one low-stock level
 * for many products at a branch, and users.branch_id gone. Timing at 26 x 20k is
 * scripts/measure-chain-scale.sh's; these hold the behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ChainScaleIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    // ------------------------------------------------------------------------ the products list

    @Test
    void atABranchTheListShowsAndFiltersThatBranchsFiguresAgainstItsOwnLevel() {
        Company c = company("List At Branch", 1);
        UUID rice = product(c, "Rice", 10);
        UUID beans = product(c, "Beans", 10);
        stockIn(c, rice, c.main(), 50);
        stockIn(c, rice, c.branches().get(1), 4);
        stockIn(c, beans, c.branches().get(1), 30);

        Map<String, Object> low = page(c.owner(), "/api/products?stockStatus=LOW&branchId=" + c.branches().get(1));
        Map<String, Object> out = page(c.owner(), "/api/products?stockStatus=OUT&branchId=" + c.main());
        Map<String, Object> all = page(c.owner(), "/api/products?branchId=" + c.branches().get(1));

        assertThat(ids(low)).as("4 against a level of 10, at that branch").containsExactly(rice.toString());
        assertThat(ids(out)).as("Head Office holds no beans").containsExactly(beans.toString());
        Map<String, Object> riceRow = row(all, rice);
        assertThat(riceRow).containsEntry("quantityOnHand", 4).containsEntry("isLowStock", true);
        assertThat((List<?>) riceRow.get("branchStock")).hasSize(2);
    }

    @Test
    void inAllBranchesLowMeansLowSomewhereAndOkMeansFineEverywhere() {
        Company c = company("List All", 1);
        UUID everywhere = product(c, "Salt", 5);
        UUID oneShort = product(c, "Sugar", 5);
        UUID none = product(c, "Oil", 5);
        for (UUID branch : c.branches()) {
            stockIn(c, everywhere, branch, 20);
        }
        stockIn(c, oneShort, c.main(), 20);
        stockIn(c, oneShort, c.branches().get(1), 2);

        Map<String, Object> low = page(c.owner(), "/api/products?stockStatus=LOW&sort=name,asc");
        Map<String, Object> ok = page(c.owner(), "/api/products?stockStatus=OK");
        Map<String, Object> out = page(c.owner(), "/api/products?stockStatus=OUT");

        assertThat(ids(low)).containsExactly(oneShort.toString());
        assertThat(low).containsEntry("totalElements", 1);
        assertThat(ids(ok)).containsExactly(everywhere.toString());
        assertThat(ids(out)).containsExactly(none.toString());
    }

    @Test
    void aBranchThePersonDoesNotSeeIsRefusedOnTheList() {
        Company c = company("List Scope", 1);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.branches().get(1));

        ResponseEntity<String> refused = restTemplate.exchange("/api/products?branchId=" + c.main(), HttpMethod.GET,
                new HttpEntity<>(auth(ade)), String.class);

        assertThat(refused.getStatusCode().is4xxClientError()).as(refused.getBody()).isTrue();
    }

    // ---------------------------------------------------------------------------- a chain's sync

    @Test
    void pastTenBranchesSyncRowsCarryTheWorkingBranchOnlyAndUpToTenCarryEvery() {
        Company chain = company("Sync Chain", 11);
        UUID rice = product(chain, "Rice", null);
        stockIn(chain, rice, chain.branches().get(3), 7);
        Company group = company("Sync Group", 2);
        product(group, "Beans", null);

        List<Map<String, Object>> atBranch = figures(chain.owner(), "&branch=" + chain.branches().get(3));
        List<Map<String, Object>> inAll = figures(chain.owner(), "");
        List<Map<String, Object>> small = figures(group.owner(), "");

        assertThat(atBranch).singleElement().satisfies(figure -> {
            assertThat(figure).containsEntry("branchId", chain.branches().get(3).toString()).containsEntry("quantityOnHand", 7);
        });
        assertThat(inAll).as("All branches at a chain: the company figure only").isNull();
        assertThat(small).as("three branches: every one").hasSize(3);
    }

    // ------------------------------------------------------------------ one level, many products

    @Test
    void oneLowStockLevelForManyProductsAtABranchAndBackToTheirOwn() {
        Company c = company("Bulk Levels", 1);
        UUID rice = product(c, "Rice", 10);
        UUID beans = product(c, "Beans", 10);
        UUID oshodi = c.branches().get(1);

        Map<String, Object> set = put(c.owner(), "/api/branches/" + oshodi + "/thresholds",
                Map.of("productIds", List.of(rice.toString(), beans.toString(), UUID.randomUUID().toString()), "lowStockThreshold", 25));
        assertThat(set).as("another company's product is skipped").containsEntry("updated", 2);
        assertThat(level(oshodi, rice)).isEqualTo(25);

        Map<String, Object> body = new HashMap<>();
        body.put("productIds", List.of(rice.toString()));
        body.put("lowStockThreshold", null);
        put(c.owner(), "/api/branches/" + oshodi + "/thresholds", body);
        assertThat(level(oshodi, rice)).isNull();
        assertThat(level(oshodi, beans)).isEqualTo(25);
    }

    @Test
    void bulkLevelsAreRefusedForABranchNotSeenAndAtAOneBranchCompany() {
        Company c = company("Bulk Refused", 1);
        UUID rice = product(c, "Rice", 10);
        Company solo = company("Bulk Solo", 0);
        UUID soloRice = product(solo, "Rice", 10);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.branches().get(1));

        assertThat(putRaw(ade, "/api/branches/" + c.main() + "/thresholds",
                Map.of("productIds", List.of(rice.toString()), "lowStockThreshold", 3)).getStatusCode().is4xxClientError()).isTrue();
        assertThat(putRaw(solo.owner(), "/api/branches/" + solo.main() + "/thresholds",
                Map.of("productIds", List.of(soloRice.toString()), "lowStockThreshold", 3)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------------------- V56

    @Test
    void usersNoLongerHaveABranchColumn() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'branch_id'
                """, Map.of(), Long.class)).isZero();
    }

    // ------------------------------------------------------------------------------- helpers

    /** {@code branches} includes the main branch first. */
    private record Company(TenantLoginResponse owner, UUID main, List<UUID> branches) {
    }

    private Company company(String name, int extraBranches) {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse owner = restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        UUID main = restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(owner)), BranchListResponse.class)
                .getBody().branches().getFirst().id();
        List<UUID> branches = new ArrayList<>(List.of(main));
        for (int i = 1; i <= extraBranches; i++) {
            ResponseEntity<BranchResponse> added = restTemplate.exchange("/api/branches", HttpMethod.POST,
                    new HttpEntity<>(Map.of("name", "Branch " + i), json(owner)), BranchResponse.class);
            assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            branches.add(added.getBody().id());
        }
        return new Company(owner, main, branches);
    }

    private UUID product(Company c, String name, Integer level) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name + " " + unique, "CS-" + unique, null, null, level, "PIECE", null, null, null), part));
        HttpHeaders headers = auth(c.owner());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private void stockIn(Company c, UUID product, UUID branch, int quantity) {
        ResponseEntity<String> response = restTemplate.exchange("/api/products/" + product + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", quantity, "branchId", branch.toString()), json(c.owner())), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private Integer level(UUID branch, UUID product) {
        return jdbc.queryForObject("SELECT low_stock_threshold FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branch).addValue("p", product), Integer.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> figures(TenantLoginResponse who, String extra) {
        Map<String, Object> snapshot = page(who, "/api/products/sync/snapshot?limit=10" + extra);
        List<Map<String, Object>> products = (List<Map<String, Object>>) snapshot.get("products");
        return (List<Map<String, Object>>) products.getFirst().get("branchStock");
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream().map(row -> row.get("id").toString()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> page, UUID id) {
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .filter(row -> row.get("id").equals(id.toString())).findFirst().orElseThrow();
    }

    private Map<String, Object> page(TenantLoginResponse who, String path) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> put(TenantLoginResponse who, String path, Object body) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, json(who)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> putRaw(TenantLoginResponse who, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, json(who)), String.class);
    }

    private TenantLoginResponse storekeeperAt(Company c, String username, UUID branch) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        body.put("branchIds", List.of(branch.toString()));
        assertThat(restTemplate.exchange("/api/users", HttpMethod.POST, new HttpEntity<>(body, json(c.owner())), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(c.owner().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    private HttpHeaders json(TenantLoginResponse who) {
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders auth(TenantLoginResponse who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(who.tokens().accessToken());
        return headers;
    }
}
