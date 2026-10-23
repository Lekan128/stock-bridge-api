package com.procurepal_services.stock_bridge_api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.analytics.dto.BranchTableResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.BranchHealthService;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.LossesResponse;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferResponse;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * Multiple branches, Phase 3 (MULTI_BRANCH_BUILD_PLAN.md section 8.1 row 3): the owner's branch table,
 * the losses report, sales and dashboard figures per branch, the phones' "waiting" report, and the
 * movements spreadsheet with its Branch column (MULTI_BRANCH_UX_PLAN.md sections 4.3, 5.7, 5.8).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class OwnerViewIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BranchHealthService branchHealthService;

    // ---------------------------------------------------------------------------- the branch table

    @Test
    void theBranchTableShowsEachBranchsWorthLevelsTransfersMissingSalesCountsAndPhones() {
        Company c = company("Branch Table");
        stockIn(c, c.rice(), c.main(), 10, "100");
        stockIn(c, c.rice(), c.oshodi(), 3, "100");
        setLevel(c, c.rice(), 5);
        // A short waybill to Oshodi (1 missing), another still on the way.
        TransferResponse shortOne = send(c, c.main(), c.oshodi(), 3);
        post(c.owner(), "/api/stock-transfers/" + shortOne.id() + "/receipts", receipt(c.rice(), 2, 1), String.class);
        send(c, c.main(), c.oshodi(), 2);
        sell(c, c.rice(), c.oshodi(), 1, "250");
        count(c, c.rice(), c.main(), 5);
        reportPhone(c.owner(), c.oshodi(), 3, 1);

        BranchTableResponse table = getAs(c.owner(), "/api/analytics/branches", BranchTableResponse.class);

        assertThat(table.rows()).extracting(BranchTableResponse.Row::name).containsExactly("Head Office", "Oshodi");
        BranchTableResponse.Row main = table.rows().get(0);
        BranchTableResponse.Row oshodi = table.rows().get(1);
        assertThat(main.stockWorth()).isEqualByComparingTo("500");
        assertThat(main.low()).as("5 against a level of 5").isEqualTo(1);
        assertThat(main.onTheWayOut()).isEqualTo(1);
        assertThat(main.lastCountAt()).isNotNull();
        assertThat(oshodi.stockWorth()).as("3 delivered + 2 received - 1 sold").isEqualByComparingTo("400");
        assertThat(oshodi.onTheWayIn()).isEqualTo(1);
        assertThat(oshodi.missing()).isEqualTo(1);
        assertThat(oshodi.salesToday()).isEqualByComparingTo("250");
        assertThat(oshodi.lastCountAt()).isNull();
        assertThat(oshodi.phonesSeen()).isEqualTo(1);
        assertThat(oshodi.waiting()).isEqualTo(3);
        assertThat(oshodi.needsYou()).isEqualTo(1);
        assertThat(main.activeProducts()).as("the census beside the alarms").isEqualTo(1);
        assertThat(table.totals().stockWorth()).isEqualByComparingTo("900");
        assertThat(table.totals().salesToday()).isEqualByComparingTo("250");
        assertThat(table.totals().onTheWay()).isEqualTo(1);
    }

    @Test
    void aStorekeeperSeesOnlyTheirBranchInTheTableAndAPhoneCannotReportForABranchItDoesNotSee() {
        Company c = company("Table Scope");
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.oshodi());
        grantAnalytics(c, ade);
        TenantLoginResponse adeAgain = login(c, "ade");

        reportPhone(adeAgain, c.main(), 2, 0);
        BranchTableResponse table = getAs(adeAgain, "/api/analytics/branches", BranchTableResponse.class);

        assertThat(table.rows()).extracting(BranchTableResponse.Row::branchId).containsExactly(c.oshodi());
        assertThat(jdbc.queryForObject("SELECT branch_id FROM phone_sync_status WHERE user_id = :u",
                new MapSqlParameterSource("u", ade.user().id()), UUID.class))
                .as("stored as no branch rather than another branch's").isNull();
    }

    // ------------------------------------------------------------------- sales and dashboard per branch

    @Test
    void salesReportAndDashboardFiguresAreTheBranchsAtABranchAndEveryBranchsTogether() {
        Company c = company("Sales Per Branch");
        stockIn(c, c.rice(), c.main(), 10, "100");
        stockIn(c, c.rice(), c.oshodi(), 2, "100");
        setLevel(c, c.rice(), 3);
        sell(c, c.rice(), c.main(), 2, "300");
        sell(c, c.rice(), c.oshodi(), 1, "250");

        Map<String, Object> atOshodi = getMap(c.owner(), "/api/analytics/sales?branchId=" + c.oshodi());
        Map<String, Object> byBranch = getMap(c.owner(), "/api/analytics/sales?groupBy=branch");
        Map<String, Object> summaryOshodi = getMap(c.owner(), "/api/analytics/summary?branchId=" + c.oshodi());
        Map<String, Object> summaryMain = getMap(c.owner(), "/api/analytics/summary?branchId=" + c.main());
        Map<String, Object> worthOshodi = getMap(c.owner(), "/api/analytics/stock-worth?branchId=" + c.oshodi());

        assertThat(new BigDecimal(totals(atOshodi).get("salesAmount").toString())).isEqualByComparingTo("250");
        List<Map<String, Object>> rows = rows(byBranch);
        assertThat(rows).extracting(row -> row.get("label")).containsExactly("Head Office", "Oshodi");
        assertThat(rows(getMap(c.owner(), "/api/analytics/sales")).getFirst()).containsKey("branch");
        assertThat(new BigDecimal(((Map<?, ?>) summaryOshodi.get("sales")).get("salesAmount").toString())).isEqualByComparingTo("250");
        assertThat(summaryOshodi).as("1 left at Oshodi, level 3").containsEntry("lowStockProductCount", 1);
        assertThat(summaryMain).as("8 left at Head Office").containsEntry("lowStockProductCount", 0);
        assertThat(new BigDecimal(worthOshodi.get("valueAtCost").toString())).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------------------------- losses

    @Test
    void theLossesReportCostsMissingDamagedCountsAndWriteOffsByKindBranchPersonAndMonth() {
        Company c = company("Losses");
        stockIn(c, c.rice(), c.main(), 20, "100");
        TransferResponse sent = send(c, c.main(), c.oshodi(), 5, "Tunde");
        post(c.owner(), "/api/stock-transfers/" + sent.id() + "/receipts", receipt(c.rice(), 2, 3), String.class);
        // One of the three turns up: two stay missing.
        post(c.owner(), "/api/stock-transfers/" + sent.id() + "/found", Map.of("id", UUID.randomUUID().toString(),
                "productId", c.rice().toString(), "quantity", 1, "branchId", c.oshodi().toString()), String.class);
        count(c, c.rice(), c.main(), 13); // 15 on the books: 2 short
        Map<String, Object> writeOff = new HashMap<>();
        writeOff.put("quantity", 1);
        writeOff.put("reason", "EXPIRED");
        writeOff.put("branchId", c.main().toString());
        assertThat(post(c.owner(), "/api/products/" + c.rice() + "/stock/stock-out", writeOff, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        LossesResponse byKind = getAs(c.owner(), "/api/stock/losses", LossesResponse.class);
        LossesResponse byBranch = getAs(c.owner(), "/api/stock/losses?groupBy=branch", LossesResponse.class);
        LossesResponse byPerson = getAs(c.owner(), "/api/stock/losses?groupBy=person", LossesResponse.class);
        LossesResponse byMonth = getAs(c.owner(), "/api/stock/losses?groupBy=month", LossesResponse.class);

        assertThat(byKind.totals().byKind().get("MISSING_IN_TRANSIT").units()).as("found stock netted out").isEqualTo(2);
        assertThat(byKind.totals().byKind().get("COUNTED_SHORT").units()).isEqualTo(2);
        assertThat(byKind.totals().byKind().get("EXPIRED").units()).isEqualTo(1);
        assertThat(byKind.totals().units()).isEqualTo(5);
        assertThat(byKind.totals().cost()).isEqualByComparingTo("500");
        assertThat(byBranch.rows()).extracting(LossesResponse.Row::label).containsExactlyInAnyOrder("Head Office", "Oshodi");
        assertThat(byPerson.rows()).extracting(LossesResponse.Row::label).contains("Tunde (carried)");
        assertThat(byMonth.rows()).hasSize(1);
        assertThat(get(c.owner(), "/api/stock/losses?groupBy=colour", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCountOverIsAGainThatComesOffTheLosses() {
        Company c = company("Losses Over");
        stockIn(c, c.rice(), c.main(), 10, "100");
        count(c, c.rice(), c.main(), 12);

        LossesResponse report = getAs(c.owner(), "/api/stock/losses", LossesResponse.class);

        assertThat(report.totals().byKind().get("COUNTED_OVER").units()).isEqualTo(2);
        assertThat(report.totals().units()).isEqualTo(-2);
        assertThat(report.totals().cost()).isEqualByComparingTo("-200");
    }

    // --------------------------------------------------------------------------- movements export

    @Test
    void theMovementsSpreadsheetNamesTheBranchOfEveryRow() throws Exception {
        Company c = company("Export");
        stockIn(c, c.rice(), c.main(), 4, "100");
        stockIn(c, c.rice(), c.oshodi(), 2, "100");

        ResponseEntity<byte[]> file = get(c.owner(), "/api/stock/movements/export", byte[].class);

        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getBody()))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            List<String> headers = new ArrayList<>();
            header.forEach(cell -> headers.add(cell.getStringCellValue()));
            int branchColumn = headers.indexOf("Branch");
            assertThat(branchColumn).isPositive();
            List<String> branches = new ArrayList<>();
            for (int i = 1; i <= workbook.getSheetAt(0).getLastRowNum(); i++) {
                Row row = workbook.getSheetAt(0).getRow(i);
                if (row != null && row.getCell(branchColumn) != null) branches.add(row.getCell(branchColumn).getStringCellValue());
            }
            assertThat(branches).containsExactlyInAnyOrder("Head Office", "Oshodi");
        }
    }

    // ------------------------------------------------------------------------------------ metrics

    @Test
    void branchHealthCarriesTheProductMetrics() {
        Company c = company("Metrics");
        stockIn(c, c.rice(), c.main(), 10, "100");
        TransferResponse sent = send(c, c.main(), c.oshodi(), 4);
        post(c.owner(), "/api/stock-transfers/" + sent.id() + "/receipts",
                Map.of("id", UUID.randomUUID().toString(), "asSent", true), String.class);

        var metrics = branchHealthService.health().metrics();

        assertThat(metrics.transfers()).isPositive();
        assertThat(metrics.averageBranches()).isGreaterThanOrEqualTo(2.0);
        assertThat(metrics.receivedAsSentPercent()).isNotNull();
    }

    // ------------------------------------------------------------------------------------ helpers

    private record Company(TenantLoginResponse owner, UUID main, UUID oshodi, UUID rice) {
    }

    private Company company(String name) {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse owner = restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com",
                        PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        restTemplate.exchange("/api/company/selling", HttpMethod.PUT,
                new HttpEntity<>(new UpdateSellingSettingsRequest(true, null), json(owner)), SellingSettingsResponse.class);
        UUID rice = product(owner, "Rice");
        UUID main = getAs(owner, "/api/branches", BranchListResponse.class).branches().getFirst().id();
        UUID oshodi = post(owner, "/api/branches", Map.of("name", "Oshodi"), BranchResponse.class).getBody().id();
        return new Company(owner, main, oshodi, rice);
    }

    private UUID product(TenantLoginResponse owner, String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name + " " + unique, "OV-" + unique, null, null, null, "PIECE", null, null, null), part));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private void stockIn(Company c, UUID product, UUID branch, int quantity, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitPrice", price);
        body.put("branchId", branch.toString());
        assertThat(post(c.owner(), "/api/products/" + product + "/stock/stock-in", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void sell(Company c, UUID product, UUID branch, int quantity, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitPrice", new BigDecimal(price).divide(BigDecimal.valueOf(quantity)));
        body.put("branchId", branch.toString());
        ResponseEntity<String> response = post(c.owner(), "/api/products/" + product + "/stock/stock-out", body, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void count(Company c, UUID product, UUID branch, int counted) {
        ResponseEntity<String> response = post(c.owner(), "/api/products/" + product + "/stock/count",
                Map.of("countedQuantity", counted, "branchId", branch.toString()), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void setLevel(Company c, UUID product, int level) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(Map.of("lowStockThreshold", level), part));
        HttpHeaders headers = auth(c.owner());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertThat(restTemplate.exchange("/api/products/" + product, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TransferResponse send(Company c, UUID from, UUID to, int quantity) {
        return send(c, from, to, quantity, null);
    }

    private TransferResponse send(Company c, UUID from, UUID to, int quantity, String driver) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("fromBranchId", from.toString());
        body.put("toBranchId", to.toString());
        if (driver != null) body.put("carriedByName", driver);
        body.put("lines", List.of(Map.of("productId", c.rice().toString(), "quantity", quantity)));
        ResponseEntity<TransferResponse> response = post(c.owner(), "/api/stock-transfers", body, TransferResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static Map<String, Object> receipt(UUID product, int received, int missing) {
        return Map.of("id", UUID.randomUUID().toString(),
                "lines", List.of(Map.of("productId", product.toString(), "received", received, "missing", missing, "damaged", 0)));
    }

    /** What a phone says on the catalogue's change feed (V55). */
    private void reportPhone(TenantLoginResponse who, UUID branch, int waiting, int needsYou) {
        String cursor = getMap(who, "/api/products/sync/snapshot?limit=1").get("cursor").toString();
        ResponseEntity<String> response = get(who, "/api/products/sync/changes?cursor=" + cursor + "&device=" + UUID.randomUUID()
                + "&waiting=" + waiting + "&needsYou=" + needsYou + "&oldestWaiting=" + System.currentTimeMillis()
                + "&branch=" + branch, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private TenantLoginResponse storekeeperAt(Company c, String username, UUID branch) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        body.put("branchIds", List.of(branch.toString()));
        assertThat(post(c.owner(), "/api/users", body, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return login(c, username);
    }

    /** A custom role like the storekeeper's, plus the reports. */
    private void grantAnalytics(Company c, TenantLoginResponse who) {
        Map<String, Object> role = post(c.owner(), "/api/roles", Map.of("name", "Branch lead " + UUID.randomUUID().toString().substring(0, 6),
                "description", "Test", "permissionCodes", List.of("VIEW_PRODUCTS", "STOCK_IN", "STOCK_OUT", "VIEW_ANALYTICS")),
                new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        ResponseEntity<String> updated = restTemplate.exchange("/api/users/" + who.user().id(), HttpMethod.PUT,
                new HttpEntity<>(Map.of("roleId", role.get("id").toString()), json(c.owner())), String.class);
        assertThat(updated.getStatusCode()).as(updated.getBody()).isEqualTo(HttpStatus.OK);
    }

    private TenantLoginResponse login(Company c, String username) {
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(c.owner().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> report) {
        return (Map<String, Object>) report.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("rows");
    }

    private Map<String, Object> getMap(TenantLoginResponse who, String path) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(who)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> T getAs(TenantLoginResponse who, String path, Class<T> type) {
        ResponseEntity<T> response = get(who, path, type);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> get(TenantLoginResponse who, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(who)), type);
    }

    private <T> ResponseEntity<T> post(TenantLoginResponse who, String path, Object body, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(who)), type);
    }

    private <T> ResponseEntity<T> post(TenantLoginResponse who, String path, Object body, ParameterizedTypeReference<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(who)), type);
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
