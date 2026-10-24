package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.analytics.BranchDigestService;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchFigure;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.CommitPreviewResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PasteRequest;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInExcelService;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The plan gaps closed after Phase 4 (MULTI_BRANCH_BUILD_PLAN.md section 8.0e): a branch's address and
 * people, the product page's per-branch extras, low stock and expected deliveries by branch, the waybill
 * refusal that names its product, the stock sheet's Branch column, the evening digest - and the section 9 cases still missing:
 * {@code BranchAccessTest}'s VIEW_ALL / WORK_AT_ALL and cost reports, {@code SellingPricePermissionTest}'s
 * create, import, legacy review, sale and migration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BranchGapsIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BranchDigestService branchDigestService;

    @Autowired
    private StockInExcelService stockInExcelService;

    // ------------------------------------------------------------------------- managing (UX 5.11)

    @Test
    void aBranchKeepsItsAddressAndSaysHowManyPeopleWorkThere() {
        TenantLoginResponse owner = signup("Branch Address");
        UUID oshodi = post(owner, "/api/branches",
                Map.of("name", "Oshodi", "address", "12 Agege Motor Road", "phone", "0803"), BranchResponse.class)
                .getBody().id();
        createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));
        createUser(owner, "bola", role("STOREKEEPER"), List.of(oshodi));

        BranchResponse listed = branch(owner, oshodi);
        assertThat(listed.address()).isEqualTo("12 Agege Motor Road");
        assertThat(listed.people()).isEqualTo(2L);

        restTemplate.exchange("/api/branches/" + oshodi, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("address", "  "), json(owner)), String.class);
        assertThat(branch(owner, oshodi).address()).as("blank clears it").isNull();
        assertThat(branch(login(owner, "ade"), oshodi).people()).as("only for people who manage branches").isNull();
    }

    // ----------------------------------------------------------------------- product page (5.9)

    @Test
    void theProductPageSaysWhenEachBranchLastCountedAndItsOwnLevel() {
        TenantLoginResponse owner = signup("Branch Detail");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of("lowStockThreshold", 5));
        stock(owner, product, "stock-in", Map.of("quantity", 10, "branchId", oshodi.toString()));
        stock(owner, product, "count", Map.of("countedQuantity", 9, "branchId", oshodi.toString()));
        put(owner, "/api/products/" + product + "/branch-thresholds",
                List.of(Map.of("branchId", oshodi.toString(), "lowStockThreshold", 12)));

        List<BranchFigure> figures = productOf(owner, product).branchStock();

        BranchFigure atOshodi = at(figures, oshodi);
        assertThat(atOshodi.lastCountedAt()).isNotNull();
        assertThat(atOshodi.ownLowStockThreshold()).isEqualTo(12);
        assertThat(atOshodi.lowStockThreshold()).isEqualTo(12);
        BranchFigure atMain = at(figures, main);
        assertThat(atMain.lastCountedAt()).as("never counted there").isNull();
        assertThat(atMain.ownLowStockThreshold()).as("uses the product's").isNull();
        assertThat(atMain.lowStockThreshold()).isEqualTo(5);
    }

    // ------------------------------------------------------------------- low stock by branch (5.1)

    @Test
    void lowStockIsLowHereAtABranchAndLowAnywhereInAllBranchesWithEachBranchsFigure() {
        TenantLoginResponse owner = signup("Branch Low");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID rice = product(owner, "Rice", Map.of("lowStockThreshold", 5));
        UUID beans = product(owner, "Beans", Map.of("lowStockThreshold", 5));
        stock(owner, rice, "stock-in", Map.of("quantity", 50, "branchId", main.toString()));
        stock(owner, rice, "stock-in", Map.of("quantity", 2, "branchId", oshodi.toString()));
        stock(owner, beans, "stock-in", Map.of("quantity", 50, "branchId", main.toString()));
        stock(owner, beans, "stock-in", Map.of("quantity", 50, "branchId", oshodi.toString()));

        List<ProductResponse> atMain = lowStock(owner, main);
        List<ProductResponse> atOshodi = lowStock(owner, oshodi);
        List<ProductResponse> anywhere = lowStock(owner, null);

        assertThat(atMain).isEmpty();
        assertThat(atOshodi).extracting(ProductResponse::id).containsExactly(rice);
        assertThat(atOshodi.getFirst().quantityOnHand()).as("that branch's figure").isEqualTo(2);
        assertThat(anywhere).extracting(ProductResponse::id).containsExactly(rice);
        assertThat(anywhere.getFirst().quantityOnHand()).as("the company's figure").isEqualTo(52);
        assertThat(at(anywhere.getFirst().branchStock(), oshodi).isLowStock()).isTrue();
        assertThat(at(anywhere.getFirst().branchStock(), main).isLowStock()).isFalse();
    }

    // ------------------------------------------------------------ expected deliveries (5.1, 5.12)

    @Test
    void anExpectedDeliveryIsListedAtTheBranchItIsGoingToAndInAllBranches() {
        TenantLoginResponse owner = signup("Branch Expected");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", oshodi.toString());
        body.put("lines", List.of(Map.of("productId", product.toString(), "unit", "PIECE", "quantity", 3)));
        ResponseEntity<String> created = post(owner, "/api/expected-deliveries", body, String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);

        assertThat(expectedCount(owner, oshodi)).isEqualTo(1);
        assertThat(expectedCount(owner, main)).isZero();
        assertThat(expectedCount(owner, null)).isEqualTo(1);
    }

    // ------------------------------------------------------ the stock-in sheet's Branch (5.12)

    @Test
    void aStockSheetRowNamingABranchGoesThereAndOneNamingNoneGoesWhereTheSheetIsFor() {
        TenantLoginResponse owner = signup("Branch Column");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID closed = addBranch(owner, "Ikeja");
        post(owner, "/api/branches/" + closed + "/close", Map.of(), String.class);
        String sku = "BC-" + UUID.randomUUID().toString().substring(0, 6);
        UUID product = product(owner, "Rice", Map.of("sku", sku));

        UUID session = paste(owner, "sku,product_name,quantity,branch\n"
                + sku + ",Rice,5,oshodi\n"
                + sku + ",Rice,3,\n", main, "STOCK_IN");
        UUID refused = paste(owner, "sku,product_name,quantity,branch\n"
                + sku + ",Rice,2,Nowhere\n"
                + sku + ",Rice,1,Ikeja\n", main, "STOCK_IN");

        assertThat(errors(owner, session)).isEmpty();
        assertThat(errors(owner, refused)).hasSize(2)
                .anyMatch(message -> message.endsWith(" is for \u201cNowhere\u201d, and there is no branch by that name."))
                .anyMatch(message -> message.endsWith(" is for Ikeja, which is closed."));
        commit(owner, session);
        assertThat(branchFigure(oshodi, product)).as("named, whatever its case").isEqualTo(5);
        assertThat(branchFigure(main, product)).as("the branch the sheet is for").isEqualTo(3);
    }

    @Test
    void aStockSheetRowForABranchThePersonDoesntWorkAtIsAnErrorOnThatRow() {
        TenantLoginResponse owner = signup("Branch Column Keeper");
        UUID main = mainBranch(owner);
        addBranch(owner, "Oshodi");
        String sku = "BK-" + UUID.randomUUID().toString().substring(0, 6);
        product(owner, "Rice", Map.of("sku", sku));
        createUser(owner, "ade", role("STOREKEEPER"), List.of(main));
        TenantLoginResponse ade = login(owner, "ade");

        UUID session = paste(ade, "sku,product_name,quantity,branch\n" + sku + ",Rice,5,Oshodi\n", null, "STOCK_IN");

        assertThat(errors(ade, session)).singleElement()
                .satisfies(message -> assertThat(message).endsWith(" is for Oshodi, and you don't work there."));
    }

    @Test
    void theStockInTemplateHasABranchColumnOnlyAtAMultiBranchCompany() throws Exception {
        assertThat(headers(stockInExcelService.generateTemplate(List.of(), true))).contains("Branch (if not the one you upload for)");
        assertThat(headers(stockInExcelService.generateTemplate(List.of(), false))).doesNotContain("Branch (if not the one you upload for)");
    }

    // ---------------------------------------------------------------- send stock (UX section 6)

    @Test
    void aWaybillRefusedForWantOfStockNamesTheProductItWasShortOf() {
        TenantLoginResponse owner = signup("Branch Short");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID rice = product(owner, "Rice", Map.of());
        UUID beans = product(owner, "Beans", Map.of());
        stock(owner, rice, "stock-in", Map.of("quantity", 20, "branchId", main.toString()));
        stock(owner, beans, "stock-in", Map.of("quantity", 3, "branchId", main.toString()));

        ResponseEntity<Map<String, Object>> refused = restTemplate.exchange("/api/stock-transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "fromBranchId", main.toString(),
                        "toBranchId", oshodi.toString(),
                        "lines", List.of(
                                Map.of("productId", rice.toString(), "quantity", 5),
                                Map.of("productId", beans.toString(), "quantity", 8))),
                        json(owner)),
                new ParameterizedTypeReference<>() {});

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("productId", beans.toString())
                .containsEntry("availableQuantity", 3)
                .containsEntry("requestedQuantity", 8);
    }

    // ------------------------------------------------------------------ evening digest (5.13)

    @Test
    void theEveningDigestGoesOnlyToThoseWhoTurnItOnAndOnlyOnceADay() {
        TenantLoginResponse owner = signup("Branch Digest");
        addBranch(owner, "Oshodi");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThat(digest(owner)).isFalse();

        put(owner, "/api/analytics/branches/digest", Map.of("enabled", true));

        assertThat(digest(owner)).isTrue();
        assertThat(branchDigestService.sendDue(today)).isPositive();
        assertThat(sentOn(owner)).isEqualTo(today);
        assertThat(dueToday(owner, today)).as("claimed for today: the next run passes them by").isFalse();

        put(owner, "/api/analytics/branches/digest", Map.of("enabled", false));
        assertThat(digest(owner)).isFalse();
    }

    // --------------------------------------------------------------- review fixes (2026-10-24)

    @Test
    void aClosedBranchIsNeitherLowNorAFigure() {
        TenantLoginResponse owner = signup("Closed Not Low");
        UUID main = mainBranch(owner);
        UUID closed = addBranch(owner, "Ikeja");
        post(owner, "/api/branches/" + closed + "/close", Map.of(), String.class);
        UUID rice = product(owner, "Rice", Map.of("lowStockThreshold", 5));
        stock(owner, rice, "stock-in", Map.of("quantity", 50, "branchId", main.toString()));

        assertThat(lowStock(owner, null)).as("fine at every open branch").isEmpty();
        assertThat(productOf(owner, rice).branchStock()).extracting(BranchFigure::branchId).containsExactly(main);
        assertThat(branches(owner).openBranches()).isEqualTo(1L);
        Map<String, Object> lowFilter = restTemplate.exchange("/api/products?stockStatus=LOW", HttpMethod.GET,
                new HttpEntity<>(auth(owner)), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        assertThat((List<?>) lowFilter.get("content")).as("Inventory's Low chip in All branches").isEmpty();
    }

    @Test
    void theCompanysOpenBranchCountIsTheSameForSomebodyWhoSeesOnlyOne() {
        TenantLoginResponse owner = signup("Open Count");
        UUID oshodi = addBranch(owner, "Oshodi");
        addBranch(owner, "Bolade");
        createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));

        BranchListResponse theirs = branches(login(owner, "ade"));

        assertThat(theirs.branches()).hasSize(1);
        assertThat(theirs.openBranches()).as("a price applies at all of them").isEqualTo(3L);
    }

    @Test
    void aLocationOrStoreColumnIsNotTakenForABranch() {
        TenantLoginResponse solo = signup("Location Column");
        String sku = "LC-" + UUID.randomUUID().toString().substring(0, 6);
        product(solo, "Rice", Map.of("sku", sku));
        UUID one = paste(solo, "sku,product_name,quantity,location\n" + sku + ",Rice,5,Aisle 3\n", null, "STOCK_IN");
        assertThat(errors(solo, one)).isEmpty();
        commit(solo, one);

        TenantLoginResponse owner = signup("Store Column");
        UUID main = mainBranch(owner);
        addBranch(owner, "Oshodi");
        String sku2 = "SC-" + UUID.randomUUID().toString().substring(0, 6);
        UUID rice = product(owner, "Rice", Map.of("sku", sku2));
        UUID two = paste(owner, "sku,product_name,quantity,store\n" + sku2 + ",Rice,4,Main store\n", main, "STOCK_IN");
        assertThat(errors(owner, two)).isEmpty();
        commit(owner, two);
        assertThat(branchFigure(main, rice)).isEqualTo(4);
    }

    @Test
    void aSheetLineForABranchClosedSinceItWasCheckedFailsOnItsOwnRow() {
        TenantLoginResponse owner = signup("Closed Since");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        String sku = "CS-" + UUID.randomUUID().toString().substring(0, 6);
        UUID rice = product(owner, "Rice", Map.of("sku", sku));
        UUID session = paste(owner, "sku,product_name,quantity,branch\n"
                + sku + ",Rice,5,Oshodi\n"
                + sku + ",Rice,3,\n", main, "STOCK_IN");
        assertThat(errors(owner, session)).isEmpty();

        assertThat(post(owner, "/api/branches/" + oshodi + "/close", Map.of(), String.class).getStatusCode().is2xxSuccessful())
                .isTrue();
        commit(owner, session);

        assertThat(branchFigure(oshodi, rice)).isZero();
        assertThat(branchFigure(main, rice)).as("the rest of the sheet still goes in").isEqualTo(3);
        assertThat(outcomeMessages(session)).contains("Not recorded: Oshodi has closed since this sheet was checked.");
    }

    @Test
    void anExpectedDeliveryCanOnlyGoToABranchThePersonWorksAt() {
        TenantLoginResponse owner = signup("Expected Elsewhere");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        createUser(owner, "ade", createRole(owner, "Receiver", Set.of("VIEW_PRODUCTS", "MANAGE_INVENTORY")), List.of(main));
        TenantLoginResponse ade = login(owner, "ade");
        Map<String, Object> body = new HashMap<>();
        body.put("lines", List.of(Map.of("productId", product.toString(), "unit", "PIECE", "quantity", 3)));

        body.put("branchId", oshodi.toString());
        assertThat(post(ade, "/api/expected-deliveries", body, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        body.put("branchId", main.toString());
        assertThat(post(ade, "/api/expected-deliveries", body, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theDigestStopsWhenTheCompanyIsBackToOneOpenBranch() {
        TenantLoginResponse owner = signup("Digest Closed");
        UUID oshodi = addBranch(owner, "Oshodi");
        put(owner, "/api/analytics/branches/digest", Map.of("enabled", true));
        post(owner, "/api/branches/" + oshodi + "/close", Map.of(), String.class);

        branchDigestService.sendDue(LocalDate.now(ZoneId.of("Africa/Lagos")));

        assertThat(sentOn(owner)).isNull();
    }

    // ----------------------------------------------------- impact on the rest of the app (2026-10-24)

    @Test
    void aSaleIsFixedOnlyBySomebodyWhoWorksAtTheBranchItWasSoldFrom() {
        TenantLoginResponse owner = signup("Sale Elsewhere");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Milk", Map.of());
        stock(owner, product, "stock-in", Map.of("quantity", 10, "branchId", oshodi.toString()));
        stock(owner, product, "stock-out", Map.of("quantity", 1, "reason", "SALE", "branchId", oshodi.toString()));
        UUID line = jdbc.queryForObject(
                "SELECT id FROM stock_movements WHERE product_id = :p AND movement_type = 'OUT'",
                new MapSqlParameterSource("p", product), UUID.class);
        createUser(owner, "boss", createRole(owner, "Main Manager",
                Set.of("VIEW_PRODUCTS", "STOCK_OUT", "MANAGE_INVENTORY")), List.of(main));
        TenantLoginResponse boss = login(owner, "boss");

        ResponseEntity<String> elsewhere = post(boss, "/api/sales/lines/" + line + "/price", Map.of("unitPrice", 500), String.class);

        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(elsewhere.getBody()).contains("You don't work at Oshodi");
        assertThat(post(owner, "/api/sales/lines/" + line + "/price", Map.of("unitPrice", 500), String.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void anExpectedDeliveryAtAnotherBranchIsNeitherShownNorCalledOffThere() {
        TenantLoginResponse owner = signup("Expected Other");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", oshodi.toString());
        body.put("lines", List.of(Map.of("productId", product.toString(), "unit", "PIECE", "quantity", 3)));
        String id = (String) restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(body, json(owner)),
                new ParameterizedTypeReference<Map<String, Object>>() {}).getBody().get("id");
        createUser(owner, "ade", createRole(owner, "Receiver", Set.of("VIEW_PRODUCTS", "MANAGE_INVENTORY")), List.of(main));
        TenantLoginResponse ade = login(owner, "ade");

        assertThat(get(ade, "/api/expected-deliveries/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(ade, "/api/expected-deliveries/" + id + "/cancel", Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(owner, "/api/expected-deliveries/" + id + "/cancel", Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aBranchWithATransferOnTheWayCannotClose() {
        TenantLoginResponse owner = signup("Close Travelling");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        stock(owner, product, "stock-in", Map.of("quantity", 5, "branchId", main.toString()));
        ResponseEntity<String> sent = post(owner, "/api/stock-transfers", Map.of(
                "id", UUID.randomUUID().toString(),
                "fromBranchId", main.toString(),
                "toBranchId", oshodi.toString(),
                "lines", List.of(Map.of("productId", product.toString(), "quantity", 5))), String.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> close = post(owner, "/api/branches/" + oshodi + "/close", Map.of(), String.class);

        assertThat(close.getStatusCode().is4xxClientError()).isTrue();
        assertThat(close.getBody()).contains("Oshodi has 1 transfer on the way. Receive or cancel it first.");
        assertThat(branch(owner, oshodi).active()).isTrue();
    }

    @Test
    void theLowStockSheetIsWhatIsLowAtTheBranch() {
        TenantLoginResponse owner = signup("Low Sheet");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID rice = product(owner, "Rice", Map.of("lowStockThreshold", 5));
        UUID beans = product(owner, "Beans", Map.of("lowStockThreshold", 5));
        stock(owner, rice, "stock-in", Map.of("quantity", 50, "branchId", main.toString()));
        stock(owner, rice, "stock-in", Map.of("quantity", 2, "branchId", oshodi.toString()));
        stock(owner, beans, "stock-in", Map.of("quantity", 50, "branchId", oshodi.toString()));

        List<Map<String, Object>> atOshodi = restTemplate.exchange(
                "/api/imports/delivery-lines?filter=LOW_STOCK&branchId=" + oshodi, HttpMethod.GET, new HttpEntity<>(auth(owner)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}).getBody();

        assertThat(atOshodi).extracting(line -> line.get("productId")).containsOnly(rice.toString());
    }

    @Test
    void theInventoryExportCarriesTheBranchsFigure() throws Exception {
        TenantLoginResponse owner = signup("Export Branch");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID rice = product(owner, "Rice", Map.of());
        stock(owner, rice, "stock-in", Map.of("quantity", 50, "branchId", main.toString()));
        stock(owner, rice, "stock-in", Map.of("quantity", 7, "branchId", oshodi.toString()));
        createUser(owner, "ade", role("STOREKEEPER"), List.of(oshodi));

        assertThat(exportedFigures(login(owner, "ade"), "")).as("their one branch, never the company's 57").contains(7.0)
                .doesNotContain(57.0);
        assertThat(exportedFigures(owner, "?branchId=" + main)).contains(50.0).doesNotContain(57.0);
        assertThat(exportedFigures(owner, "")).as("All branches").contains(57.0);
    }

    // ------------------------------------------------------------- BranchAccessTest (section 9)

    @Test
    void viewAllBranchesReadsEveryBranchButRecordsOnlyWhereThePersonWorks() {
        TenantLoginResponse owner = signup("View All");
        UUID main = mainBranch(owner);
        UUID oshodi = addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        stock(owner, product, "stock-in", Map.of("quantity", 7, "branchId", oshodi.toString()));
        UUID roleId = createRole(owner, "Area Supervisor",
                Set.of("VIEW_PRODUCTS", "STOCK_IN", "VIEW_ALL_BRANCHES", "VIEW_ANALYTICS", "MANAGE_INVENTORY"));
        createUser(owner, "sup", roleId, List.of(main));
        TenantLoginResponse sup = login(owner, "sup");

        BranchListResponse list = branches(sup);
        assertThat(list.branches()).extracting(BranchResponse::id).contains(main, oshodi);
        assertThat(list.seesEverywhere()).isTrue();
        assertThat(at(productOf(sup, product).branchStock(), oshodi).quantityOnHand()).isEqualTo(7);
        assertThat(get(sup, "/api/stock/movements?branchId=" + oshodi).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(stockRaw(sup, product, "stock-in", Map.of("quantity", 1, "branchId", oshodi.toString())).getStatusCode())
                .as("reads it, never records there").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stockRaw(sup, product, "stock-in", Map.of("quantity", 1, "branchId", main.toString())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void workAtAllBranchesRecordsAtABranchAddedAfterTheRoleWasGiven() {
        TenantLoginResponse owner = signup("Work At All");
        addBranch(owner, "Oshodi");
        UUID product = product(owner, "Rice", Map.of());
        UUID roleId = createRole(owner, "Roaming Keeper", Set.of("VIEW_PRODUCTS", "STOCK_IN", "WORK_AT_ALL_BRANCHES"));
        createUser(owner, "roam", roleId, null);
        TenantLoginResponse roam = login(owner, "roam");

        UUID bolade = addBranch(owner, "Bolade");

        ResponseEntity<String> in = stockRaw(roam, product, "stock-in", Map.of("quantity", 4, "branchId", bolade.toString()));
        assertThat(in.getStatusCode()).as(in.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(branchFigure(bolade, product)).isEqualTo(4);
        assertThat(branches(roam).recordsEverywhere()).isTrue();
    }

    @Test
    void aViewOnlyRoleWithoutTheReportPermissionsIsRefusedEveryCostReport() {
        TenantLoginResponse owner = signup("View Only Costs");
        UUID roleId = createRole(owner, "Looker", Set.of("VIEW_PRODUCTS", "VIEW_ALL_BRANCHES"));
        createUser(owner, "look", roleId, null);
        addBranch(owner, "Oshodi");
        TenantLoginResponse look = login(owner, "look");

        for (String path : List.of("/api/stock/losses", "/api/analytics/stock-worth", "/api/analytics/branches",
                "/api/analytics/summary", "/api/stock/movements")) {
            assertThat(get(look, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(get(look, "/api/products").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------ SellingPricePermissionTest (section 9)

    @Test
    void withoutChangeSellingPricesACreatedProductHasNoPrice() {
        TenantLoginResponse owner = signup("Price Create");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        createUser(owner, "clerk", catalogueClerk(owner), null);
        TenantLoginResponse clerk = login(owner, "clerk");

        UUID product = product(clerk, "Milk", Map.of("unitPrice", 650));

        assertThat(productOf(owner, product).unitPrice()).as("saved, without the price").isNull();
    }

    @Test
    void withoutChangeSellingPricesAnImportAppliesTheRowMinusItsPrice() {
        TenantLoginResponse owner = signup("Price Import");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        createUser(owner, "clerk", catalogueClerk(owner), null);
        addBranch(owner, "Oshodi");
        TenantLoginResponse clerk = login(owner, "clerk");
        String sku = "PI-" + UUID.randomUUID().toString().substring(0, 6);
        String sheet = "Product name\tYour code\tSelling price\nSugar\t" + sku + "\t1200\n";

        UUID clerkSession = paste(clerk, sheet, null);
        assertThat(preview(clerk, clerkSession).lines()).extracting(CommitPreviewResponse.Line::key)
                .as("no prices line: their prices won't apply").doesNotContain("prices");
        commit(clerk, clerkSession);

        Map<String, Object> created = jdbc.queryForMap("SELECT name, unit_price FROM products WHERE sku = :sku",
                new MapSqlParameterSource("sku", sku));
        assertThat(created.get("name")).isEqualTo("Sugar");
        assertThat(created.get("unit_price")).isNull();

        String ownerSku = sku + "-O";
        UUID ownerSession = paste(owner, "Product name\tYour code\tSelling price\nSalt\t" + ownerSku + "\t300\n", mainBranch(owner));
        assertThat(preview(owner, ownerSession).lines())
                .filteredOn(line -> line.key().equals("prices"))
                .singleElement()
                .satisfies(line -> assertThat(line.text()).isEqualTo("1 price is set, at all 2 branches"));
    }

    @Test
    void withoutChangeSellingPricesTheOldPriceReviewIsRefused() {
        TenantLoginResponse owner = signup("Price Legacy");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        createUser(owner, "clerk", catalogueClerk(owner), null);
        TenantLoginResponse clerk = login(owner, "clerk");

        ResponseEntity<String> adopt = post(clerk, "/api/company/selling/legacy-prices/adopt", Map.of("all", true), String.class);

        assertThat(adopt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adopt.getBody()).contains("Prices are set by people who can change selling prices");
    }

    @Test
    void withoutChangeSellingPricesASaleCanStillBeMadeAtAnyPrice() {
        TenantLoginResponse owner = signup("Price Sale");
        put(owner, "/api/company/selling", Map.of("sellsStock", true));
        UUID product = product(owner, "Milk", Map.of("unitPrice", 500));
        stock(owner, product, "stock-in", Map.of("quantity", 10));
        createUser(owner, "till", createRole(owner, "Till", Set.of("VIEW_PRODUCTS", "STOCK_OUT")), null);
        TenantLoginResponse till = login(owner, "till");

        ResponseEntity<String> sold = stockRaw(till, product, "stock-out", Map.of("quantity", 1, "unitPrice", 450));

        assertThat(sold.getStatusCode()).as(sold.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(productOf(owner, product).unitPrice()).as("the list price is untouched").isEqualByComparingTo("500");
    }

    /**
     * V52: every role that held MANAGE_PRODUCTS before it, system or custom, was given the new one. A
     * custom role is judged by its company's age (roles carry no date of their own).
     */
    @Test
    void theMigrationGaveChangeSellingPricesToEveryRoleThatCouldManageProducts() {
        Long missing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN permissions mp ON mp.id = rp.permission_id AND mp.code = 'MANAGE_PRODUCTS'
                JOIN roles r ON r.id = rp.role_id
                WHERE (r.client_id IS NULL OR (SELECT c.created_at FROM clients c WHERE c.id = r.client_id)
                       < (SELECT installed_on FROM flyway_schema_history WHERE version = '52'))
                  AND NOT EXISTS (SELECT 1 FROM role_permissions c JOIN permissions cp ON cp.id = c.permission_id
                                  WHERE c.role_id = rp.role_id AND cp.code = 'CHANGE_SELLING_PRICES')
                """, new MapSqlParameterSource(), Long.class);
        assertThat(missing).isZero();
        for (String system : List.of("OWNER", "PROCUREMENT_MANAGER")) {
            Long has = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                    JOIN roles r ON r.id = rp.role_id
                    WHERE r.client_id IS NULL AND r.name = :name AND p.code = 'CHANGE_SELLING_PRICES'
                    """, new MapSqlParameterSource("name", system), Long.class);
            assertThat(has).as(system).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private UUID catalogueClerk(TenantLoginResponse owner) {
        return createRole(owner, "Catalogue Clerk", Set.of("VIEW_PRODUCTS", "MANAGE_PRODUCTS", "MANAGE_INVENTORY"));
    }

    private static BranchFigure at(List<BranchFigure> figures, UUID branchId) {
        return figures.stream().filter(figure -> figure.branchId().equals(branchId)).findFirst().orElseThrow();
    }

    private List<ProductResponse> lowStock(TenantLoginResponse who, UUID branchId) {
        return restTemplate.exchange("/api/products/low-stock" + (branchId == null ? "" : "?branchId=" + branchId),
                HttpMethod.GET, new HttpEntity<>(auth(who)), new ParameterizedTypeReference<List<ProductResponse>>() {})
                .getBody();
    }

    private int expectedCount(TenantLoginResponse who, UUID branchId) {
        Map<String, Object> page = restTemplate.exchange(
                "/api/expected-deliveries" + (branchId == null ? "" : "?branchId=" + branchId),
                HttpMethod.GET, new HttpEntity<>(auth(who)), new ParameterizedTypeReference<Map<String, Object>>() {})
                .getBody();
        return ((List<?>) page.get("content")).size();
    }

    private boolean digest(TenantLoginResponse who) {
        return Boolean.TRUE.equals(restTemplate.exchange("/api/analytics/branches/digest", HttpMethod.GET,
                new HttpEntity<>(auth(who)), new ParameterizedTypeReference<Map<String, Boolean>>() {}).getBody().get("enabled"));
    }

    private LocalDate sentOn(TenantLoginResponse who) {
        return jdbc.queryForObject("SELECT branch_digest_sent_on FROM users WHERE id = :id",
                new MapSqlParameterSource("id", who.user().id()), LocalDate.class);
    }

    /** Opted in and not yet sent today's: what the next run would send this person. */
    private boolean dueToday(TenantLoginResponse who, LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT branch_digest AND branch_digest_sent_on IS DISTINCT FROM :today FROM users WHERE id = :id",
                new MapSqlParameterSource().addValue("today", today).addValue("id", who.user().id()), Boolean.class));
    }

    private UUID paste(TenantLoginResponse who, String text, UUID branchId) {
        return paste(who, text, branchId, "PRODUCT_CATALOG");
    }

    private UUID paste(TenantLoginResponse who, String text, UUID branchId, String kind) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange("/api/imports/paste", HttpMethod.POST,
                new HttpEntity<>(new PasteRequest(text, kind, null, null, null, null, branchId), json(who)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s", response.getBody()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    /** Every Branch error on the session's rows. */
    @SuppressWarnings("unchecked")
    private List<String> errors(TenantLoginResponse who, UUID session) {
        Map<String, Object> page = restTemplate.exchange("/api/imports/" + session + "/rows?status=ALL&size=200",
                HttpMethod.GET, new HttpEntity<>(auth(who)), new ParameterizedTypeReference<Map<String, Object>>() {})
                .getBody();
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .flatMap(row -> ((List<Map<String, Object>>) row.getOrDefault("errors", List.of())).stream())
                .filter(error -> "branch".equals(error.get("column")))
                .map(error -> (String) error.get("message"))
                .toList();
    }

    /** What each row's commit said happened (kept with the row, and read back by the report). */
    private List<String> outcomeMessages(UUID session) {
        return jdbc.queryForList(
                "SELECT COALESCE(normalized ->> '_outcome_message', '') FROM import_session_rows WHERE session_id = :id",
                new MapSqlParameterSource("id", session), String.class);
    }

    /** Every number on the export's data rows. */
    private List<Double> exportedFigures(TenantLoginResponse who, String query) throws Exception {
        byte[] sheet = restTemplate.exchange("/api/products/export" + query, HttpMethod.GET, new HttpEntity<>(auth(who)),
                byte[].class).getBody();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(sheet))) {
            List<Double> numbers = new java.util.ArrayList<>();
            var products = book.getSheetAt(0);
            for (int r = 2; r <= products.getLastRowNum(); r++) {
                var row = products.getRow(r);
                if (row != null) {
                    row.forEach(cell -> {
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            numbers.add(cell.getNumericCellValue());
                        }
                    });
                }
            }
            return numbers;
        }
    }

    private static List<String> headers(byte[] workbook) throws Exception {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(workbook))) {
            List<String> cells = new java.util.ArrayList<>();
            for (var sheet : book) {
                for (int r = 0; r <= Math.min(10, sheet.getLastRowNum()); r++) {
                    var row = sheet.getRow(r);
                    if (row != null) {
                        row.forEach(cell -> cells.add(cell.toString()));
                    }
                }
            }
            return cells;
        }
    }

    private CommitPreviewResponse preview(TenantLoginResponse who, UUID session) {
        return restTemplate.exchange("/api/imports/" + session + "/preview", HttpMethod.GET, new HttpEntity<>(auth(who)),
                CommitPreviewResponse.class).getBody();
    }

    private void commit(TenantLoginResponse who, UUID session) {
        ResponseEntity<String> response = restTemplate.exchange("/api/imports/" + session + "/commit", HttpMethod.POST,
                new HttpEntity<>(auth(who)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private BranchListResponse branches(TenantLoginResponse who) {
        return restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(who)), BranchListResponse.class)
                .getBody();
    }

    private BranchResponse branch(TenantLoginResponse who, UUID id) {
        return branches(who).branches().stream().filter(branch -> branch.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID mainBranch(TenantLoginResponse owner) {
        return branches(owner).branches().getFirst().id();
    }

    private UUID addBranch(TenantLoginResponse owner, String name) {
        ResponseEntity<BranchResponse> response = post(owner, "/api/branches", Map.of("name", name), BranchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private int branchFigure(UUID branchId, UUID productId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branchId).addValue("p", productId), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private void stock(TenantLoginResponse who, UUID product, String action, Map<String, Object> body) {
        ResponseEntity<String> response = stockRaw(who, product, action, body);
        assertThat(response.getStatusCode()).as("%s %s: %s", action, body, response.getBody()).isEqualTo(HttpStatus.OK);
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

    private void createUser(TenantLoginResponse owner, String username, UUID roleId, List<UUID> branchIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleId.toString());
        if (branchIds != null) {
            body.put("branchIds", branchIds.stream().map(UUID::toString).toList());
        }
        ResponseEntity<UserSummaryResponse> response = post(owner, "/api/users", body, UserSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private TenantLoginResponse login(TenantLoginResponse owner, String username) {
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    private UUID product(TenantLoginResponse who, String name, Map<String, Object> extra) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> fields = new HashMap<>(extra);
        fields.put("name", name + " " + unique);
        fields.putIfAbsent("sku", "BG-" + unique);
        fields.put("unitOfMeasure", "PIECE");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("product", new HttpEntity<>(fields, jsonOnly()));
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ProductResponse productOf(TenantLoginResponse who, UUID product) {
        return restTemplate.exchange("/api/products/" + product, HttpMethod.GET, new HttpEntity<>(auth(who)),
                ProductResponse.class).getBody();
    }

    private ResponseEntity<String> get(TenantLoginResponse who, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(who)), String.class);
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
