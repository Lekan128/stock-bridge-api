package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.math.BigDecimal;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Selling till Step 4 (SELLING_TILL_PLAN.md): things a company makes and sells but doesn't keep in stock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MadeHereIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void madeHereItemsWaitForTheSwitchWhichWaitsForSelling() {
        Shop shop = shop("Made Switch Shop");
        assertThat(madeHereProduct(shop, "Pepper soup", "6500", null, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        sellingOn(shop);
        Map<?, ?> settings = send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), Map.class).getBody();
        assertThat(settings.get("sellsMadeHere")).isEqualTo(true);
        assertThat(settings.get("madeHereChangedByName")).isNotNull();
        assertThat(madeHereProduct(shop, "Pepper soup", "6500", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aMadeHereItemSellsWithNoStockCostedFromItsRoughCostAndNothingElseTouchesIt() {
        Shop shop = shop("Made Sell Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        ProductResponse soup = madeHereProduct(shop, "Pepper soup", "6500", "2800").getBody();
        assertThat(soup.madeHere()).isTrue();
        assertThat(soup.estimatedCost()).isEqualByComparingTo("2800");
        assertThat(soup.lowStockThreshold()).isNull();
        assertThat(soup.unitOfMeasure()).isEqualTo("PIECE");

        UUID saleId = UUID.randomUUID();
        StockMutationResponse sold = send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 2, "reason", "SALE", "saleId", saleId.toString(), "lineTotal", 13000, "paymentMethod", "CASH"),
                StockMutationResponse.class).getBody();
        assertThat(sold.movement().saleAmount()).isEqualByComparingTo("13000");
        assertThat(sold.movement().costAmount()).isEqualByComparingTo("5600");
        assertThat(sold.movement().costBasis()).hasToString("ESTIMATE");
        assertThat(onHand(shop, soup.id())).isEqualByComparingTo("0");

        // Nothing about stock means anything for it.
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-in", Map.of("quantity", 5), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/count", Map.of("countedQuantity", 5), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "DAMAGED"), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Cancelling the sale gives nothing "back": there was no stock.
        SaleResponse cancelled = send(shop, HttpMethod.POST, "/api/sales/" + saleId + "/cancel", Map.of("reason", "WRONG_ITEM"),
                SaleResponse.class).getBody();
        assertThat(cancelled.cancelState()).isEqualTo("CANCELLED");
        assertThat(onHand(shop, soup.id())).isEqualByComparingTo("0");

        // The Sales report counts it like any product; profit from the rough cost.
        UUID again = UUID.randomUUID();
        send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", again.toString(), "lineTotal", 6500, "paymentMethod", "CARD"),
                StockMutationResponse.class);
        SalesReportResponse report = get(shop, "/api/analytics/sales?groupBy=product", SalesReportResponse.class).getBody();
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.totals().salesAmount()).isEqualByComparingTo("6500");
            assertThat(row.totals().grossProfit()).isEqualByComparingTo("3700");
        });
    }

    @Test
    void aMadeHereItemIsLeftOutOfStockFiguresAndItsRoughCostCanChange() {
        Shop shop = shop("Made Figures Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        ProductResponse soup = madeHereProduct(shop, "Asun", "5000", null).getBody();

        // No unit, and nothing "needs a quick fix" for it.
        assertThat(get(shop, "/api/products/data-issues", String.class).getBody()).doesNotContain(soup.id().toString());

        String worth = get(shop, "/api/analytics/stock-worth", String.class).getBody();
        assertThat(worth).contains("\"activeProductCount\":0");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(Map.of("estimatedCost", 1900), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ProductResponse updated = restTemplate.exchange("/api/products/" + soup.id(), HttpMethod.PUT,
                new HttpEntity<>(body, headers), ProductResponse.class).getBody();
        assertThat(updated.estimatedCost()).isEqualByComparingTo("1900");
    }

    @Test
    void atACompanyWithTwoBranchesAMadeHereSaleBelongsToTheBranchItWasSoldAt() {
        Shop shop = shop("Made Branches Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        ResponseEntity<Map> created = send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Oshodi"), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("second branch: %s", created.getBody()).isTrue();
        String oshodi = (String) created.getBody().get("id");
        ProductResponse soup = madeHereProduct(shop, "Pepper soup", "6500", "2800").getBody();
        // Saying no branch, it is the main branch's (V92); Oshodi is given it as a new branch would be.
        send(shop, HttpMethod.POST, "/api/branches/" + oshodi + "/products", Map.of("productIds", java.util.List.of(soup.id().toString())), Map.class);

        StockMutationResponse sold = send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 6500,
                        "paymentMethod", "CASH", "branchId", oshodi),
                StockMutationResponse.class).getBody();
        assertThat(sold.movement().branchId()).hasToString(oshodi);

        SalesReportResponse byBranch = get(shop, "/api/analytics/sales?groupBy=branch", SalesReportResponse.class).getBody();
        assertThat(byBranch.rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("Oshodi");
            assertThat(row.totals().salesAmount()).isEqualByComparingTo("6500");
        });
        // No stock figure at either branch.
        assertThat(onHand(shop, soup.id())).isEqualByComparingTo("0");
    }

    @Test
    void aProductIsOnlyForItsBranchesAndNothingIsRecordedForItAnywhereElse() {
        Shop shop = shop("For Branch Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        String main = (String) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");

        // A made-here item for the main branch only.
        Map<String, Object> soupFields = new java.util.HashMap<>(Map.of("name", "Pepper soup", "sku", "PS-" + UUID.randomUUID().toString().substring(0, 6),
                "unitPrice", 6500, "madeHere", true, "branchIds", java.util.List.of(main)));
        ProductResponse soup = productWith(shop, soupFields);
        assertThat(soup.forAllBranches()).isFalse();
        assertThat(soup.forBranchIds()).containsExactly(UUID.fromString(main));

        ResponseEntity<String> refused = send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 6500, "branchId", ikeja),
                String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("isn't for Ikeja");
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + soup.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 6500, "branchId", main),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The Ikeja list doesn't have it; the main branch's does.
        assertThat(get(shop, "/api/products?branchId=" + ikeja, String.class).getBody()).doesNotContain(soup.id().toString());
        assertThat(get(shop, "/api/products?branchId=" + main, String.class).getBody()).contains(soup.id().toString());

        // A stocked product for Ikeja only: counted there, refused at the main branch; stock arriving at the main
        // branch makes it for the main branch too.
        Map<String, Object> cokeFields = new java.util.HashMap<>(Map.of("name", "Coke", "sku", "CK-" + UUID.randomUUID().toString().substring(0, 6),
                "unitPrice", 500, "branchIds", java.util.List.of(ikeja)));
        ProductResponse coke = productWith(shop, cokeFields);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + coke.id() + "/stock/count",
                Map.of("countedQuantity", 4, "branchId", main), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + coke.id() + "/stock/stock-in",
                Map.of("quantity", 6, "branchId", main), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(shop, "/api/products/" + coke.id(), ProductResponse.class).getBody().forBranchIds())
                .containsExactlyInAnyOrder(UUID.fromString(ikeja), UUID.fromString(main));

        // "All branches": everywhere.
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(Map.of("allBranches", true), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ProductResponse everywhere = restTemplate.exchange("/api/products/" + soup.id(), HttpMethod.PUT,
                new HttpEntity<>(body, headers), ProductResponse.class).getBody();
        assertThat(everywhere.forAllBranches()).isTrue();
        assertThat(get(shop, "/api/products?branchId=" + ikeja, String.class).getBody()).contains(soup.id().toString());
    }

    @Test
    void aNewBranchIsGivenProductsChosenOrAllWithNoStock() {
        Shop shop = shop("Branch Products Shop");
        String main = (String) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        ProductResponse coke = productWith(shop, new java.util.HashMap<>(Map.of("name", "Coke", "sku", "CK-" + UUID.randomUUID().toString().substring(0, 6), "branchIds", java.util.List.of(main))));
        ProductResponse fanta = productWith(shop, new java.util.HashMap<>(Map.of("name", "Fanta", "sku", "FT-" + UUID.randomUUID().toString().substring(0, 6), "branchIds", java.util.List.of(main))));
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");

        Map<String, Object> toAdd = get(shop, "/api/branches/" + ikeja + "/products/to-add?search=cok", Map.class).getBody();
        assertThat(((Number) toAdd.get("total")).intValue()).isEqualTo(2);
        assertThat(((Number) toAdd.get("matching")).intValue()).isEqualTo(1);

        Map<String, Object> added = send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products",
                Map.of("productIds", java.util.List.of(coke.id().toString())), Map.class).getBody();
        assertThat(((Number) added.get("added")).intValue()).isEqualTo(1);
        assertThat(get(shop, "/api/products?branchId=" + ikeja, String.class).getBody())
                .contains(coke.id().toString()).doesNotContain(fanta.id().toString());
        // No stock moved: counting it there is now allowed, and it holds nothing.
        assertThat(get(shop, "/api/products/" + coke.id(), ProductResponse.class).getBody().quantityOnHand()).isEqualByComparingTo("0");

        assertThat(((Number) send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products", Map.of("all", true), Map.class)
                .getBody().get("added")).intValue()).isEqualTo(1);
        assertThat(((Number) get(shop, "/api/branches/" + ikeja + "/products/to-add", Map.class).getBody().get("total")).intValue()).isZero();
    }

    @Test
    void aPlateTakesItsIngredientsFromWhereItIsMadeAndCancellingPutsThemBack() {
        Shop shop = shop("Made With Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        String main = (String) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        java.util.List<Map<String, Object>> areas = send(shop, HttpMethod.POST, "/api/branches/" + main + "/areas",
                Map.of("areas", java.util.List.of(Map.of("name", "Kitchen", "keepsStock", true), Map.of("name", "Front desk", "keepsStock", true))),
                java.util.List.class).getBody();
        String kitchen = areas.stream().filter(area -> "Kitchen".equals(area.get("name"))).findFirst().orElseThrow().get("id").toString();
        String frontDesk = areas.stream().filter(area -> "Front desk".equals(area.get("name"))).findFirst().orElseThrow().get("id").toString();

        ProductResponse rice = productWith(shop, new java.util.HashMap<>(Map.of("name", "Rice", "sku", "RC-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "KG", "packagingUnit", "BAG", "packagingSize", 50, "branchIds", java.util.List.of(main),
                "measures", java.util.List.of(Map.of("name", "Derica", "size", 0.6)))));
        ProductResponse chicken = productWith(shop, new java.util.HashMap<>(Map.of("name", "Chicken", "sku", "CH-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "branchIds", java.util.List.of(main))));
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + rice.id() + "/stock/stock-in",
                Map.of("quantity", 2, "unit", "BAG", "unitPrice", 72000, "branchId", main, "areaId", kitchen), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + chicken.id() + "/stock/stock-in",
                Map.of("quantity", 2, "unitPrice", 1200, "branchId", main, "areaId", kitchen), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ProductResponse plate = productWith(shop, new java.util.HashMap<>(Map.of("name", "Jollof rice & chicken", "sku", "JR-" + UUID.randomUUID().toString().substring(0, 6),
                "unitPrice", 7000, "madeHere", true, "branchIds", java.util.List.of(main))));
        ResponseEntity<Map> saved = send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-with", Map.of(
                "madeIn", "Kitchen",
                "ingredients", java.util.List.of(
                        Map.of("productId", rice.id().toString(), "quantity", 1, "unit", "Derica"),
                        Map.of("productId", chicken.id().toString(), "quantity", 1))), Map.class);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        // At cost price: 0.6 kg x 1,440 + 1 x 1,200.
        assertThat(new java.math.BigDecimal(saved.getBody().get("costToMake").toString())).isEqualByComparingTo("2064.00");

        // 3 plates at the Front desk: the Kitchen's rice and chicken go down, chicken below 0 - never stopped.
        UUID saleId = UUID.randomUUID();
        StockMutationResponse sold = send(shop, HttpMethod.POST, "/api/products/" + plate.id() + "/stock/stock-out",
                Map.of("quantity", 3, "reason", "SALE", "saleId", saleId.toString(), "lineTotal", 21000,
                        "paymentMethod", "TRANSFER", "branchId", main, "areaId", frontDesk),
                StockMutationResponse.class).getBody();
        assertThat(kitchenHas(rice.id(), kitchen)).isEqualByComparingTo("98.2");
        assertThat(kitchenHas(chicken.id(), kitchen)).isEqualByComparingTo("-1");
        // Costed from the stock used: 1.8 kg at 1,440 a kg, 2 chickens at 1,200 and 1 at the average (1,200).
        java.math.BigDecimal cost = jdbcTemplate.queryForObject(
                "SELECT cost_amount FROM stock_movements WHERE id = ?", java.math.BigDecimal.class, sold.movement().id());
        assertThat(cost).isEqualByComparingTo("6192.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE used_for_movement_id = ? AND movement_type = 'OUT'",
                Integer.class, sold.movement().id())).isEqualTo(2);

        // Cancelled: both back in the Kitchen.
        assertThat(send(shop, HttpMethod.POST, "/api/sales/" + saleId + "/cancel", Map.of("reason", "WRONG_ITEM"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kitchenHas(rice.id(), kitchen)).isEqualByComparingTo("100");
        assertThat(kitchenHas(chicken.id(), kitchen)).isEqualByComparingTo("2");

        // A second branch with no Kitchen: the plate can't be saved for it until its area there is chosen.
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");
        send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products", Map.of("productIds", java.util.List.of(plate.id().toString())), Map.class);
        Map<String, Object> recipe = Map.of("madeIn", "Kitchen", "ingredients", java.util.List.of(
                Map.of("productId", rice.id().toString(), "quantity", 1, "unit", "Derica")));
        ResponseEntity<String> refused = send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-with", recipe, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Ikeja has no Kitchen");
        String ikejaStore = ((java.util.List<Map<String, Object>>) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class)
                .getBody().get("branches")).stream().filter(b -> ikeja.equals(b.get("id"))).findFirst().orElseThrow().get("areas"))
                .get(0).get("id").toString();
        Map<String, Object> withIkeja = new java.util.HashMap<>(recipe);
        withIkeja.put("madeInAt", java.util.List.of(Map.of("branchId", ikeja, "areaId", ikejaStore)));
        assertThat(send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-with", withIkeja, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // The rice followed the plate to Ikeja, with no stock.
        assertThat(get(shop, "/api/products/" + rice.id(), ProductResponse.class).getBody().forBranchIds()).contains(UUID.fromString(ikeja));
    }

    @Test
    void stockMovementsSaysWhatTheKitchenUsedAgainstWhatItsCountFound() {
        Shop shop = shop("Kitchen Use Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        String main = (String) ((java.util.List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .get(0).get("id");
        java.util.List<Map<String, Object>> areas = send(shop, HttpMethod.POST, "/api/branches/" + main + "/areas",
                Map.of("areas", java.util.List.of(Map.of("name", "Kitchen", "keepsStock", true), Map.of("name", "Front desk", "keepsStock", true))),
                java.util.List.class).getBody();
        String kitchen = areas.stream().filter(area -> "Kitchen".equals(area.get("name"))).findFirst().orElseThrow().get("id").toString();
        String frontDesk = areas.stream().filter(area -> "Front desk".equals(area.get("name"))).findFirst().orElseThrow().get("id").toString();
        ProductResponse rice = productWith(shop, new java.util.HashMap<>(Map.of("name", "Rice", "sku", "RC-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "KG", "branchIds", java.util.List.of(main), "measures", java.util.List.of(Map.of("name", "Derica", "size", 0.6)))));
        send(shop, HttpMethod.POST, "/api/products/" + rice.id() + "/stock/stock-in",
                Map.of("quantity", 100, "unitPrice", 1440, "branchId", main, "areaId", kitchen), String.class);
        ProductResponse plate = productWith(shop, new java.util.HashMap<>(Map.of("name", "Jollof rice", "sku", "JR-" + UUID.randomUUID().toString().substring(0, 6),
                "unitPrice", 3000, "madeHere", true, "branchIds", java.util.List.of(main))));
        send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-with", Map.of("madeIn", "Kitchen",
                "ingredients", java.util.List.of(Map.of("productId", rice.id().toString(), "quantity", 1, "unit", "Derica"))), Map.class);
        send(shop, HttpMethod.POST, "/api/products/" + plate.id() + "/stock/stock-out",
                Map.of("quantity", 3, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 9000,
                        "paymentMethod", "CASH", "branchId", main, "areaId", frontDesk), String.class);

        String url = "/api/stock/movements/area-use?branchId=" + main + "&areaId=" + kitchen;
        ResponseEntity<String> raw = get(shop, url, String.class);
        assertThat(raw.getStatusCode().is2xxSuccessful()).as("%s %s", raw.getStatusCode(), raw.getBody()).isTrue();
        Map<String, Object> use = get(shop, url, Map.class).getBody();
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) use.get("ingredients");
        assertThat(lines).hasSize(1);
        assertThat(new java.math.BigDecimal(lines.get(0).get("cameIn").toString())).isEqualByComparingTo("100");
        assertThat(new java.math.BigDecimal(lines.get(0).get("platesTook").toString())).isEqualByComparingTo("1.8");
        assertThat(new java.math.BigDecimal(lines.get(0).get("left").toString())).isEqualByComparingTo("98.2");
        assertThat(new java.math.BigDecimal(use.get("platesTookCost").toString())).isEqualByComparingTo("2592.00");
        assertThat(use.get("counted")).isEqualTo(false);

        // The Kitchen counted at 97 kg: 1.2 kg used on top of the plates.
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + rice.id() + "/stock/count",
                Map.of("countedQuantity", 97, "branchId", main, "areaId", kitchen), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        use = get(shop, url, Map.class).getBody();
        lines = (java.util.List<Map<String, Object>>) use.get("ingredients");
        assertThat(use.get("counted")).isEqualTo(true);
        assertThat(new java.math.BigDecimal(lines.get(0).get("usedOnTop").toString())).isEqualByComparingTo("1.2");
        assertThat(new java.math.BigDecimal(use.get("usedOnTopCost").toString())).isEqualByComparingTo("1728.00");

        // The Front desk made nothing: no line there.
        assertThat((java.util.List<?>) get(shop, "/api/stock/movements/area-use?branchId=" + main + "&areaId=" + frontDesk, Map.class)
                .getBody().get("ingredients")).isEmpty();
    }

    private java.math.BigDecimal kitchenHas(UUID productId, String areaId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE((SELECT quantity_on_hand FROM area_stock WHERE product_id = ? AND area_id = ?::uuid), 0)",
                java.math.BigDecimal.class, productId, areaId);
    }

    private ProductResponse productWith(Shop shop, Map<String, Object> fields) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(fields, part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void sellingOn(Shop shop) {
        assertThat(send(shop, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<ProductResponse> madeHereProduct(Shop shop, String name, String price, String roughCost) {
        return madeHereProduct(shop, name, price, roughCost, ProductResponse.class);
    }

    private <T> ResponseEntity<T> madeHereProduct(Shop shop, String name, String price, String roughCost, Class<T> type) {
        Map<String, Object> product = new java.util.HashMap<>();
        product.put("name", name);
        product.put("sku", name.toUpperCase().replace(' ', '-') + "-" + UUID.randomUUID().toString().substring(0, 6));
        product.put("unitPrice", new BigDecimal(price));
        product.put("madeHere", true);
        if (roughCost != null) product.put("estimatedCost", new BigDecimal(roughCost));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(product, part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private BigDecimal onHand(Shop shop, UUID productId) {
        return get(shop, "/api/products/" + productId, ProductResponse.class).getBody().quantityOnHand();
    }

    // ------------------------------------------------------------------------- helpers ----

    private record Shop(TenantLoginResponse login) {
    }

    private static Map<String, Object> sale(int quantity, String unitPrice, UUID saleId, String paymentMethod) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitPrice", new BigDecimal(unitPrice));
        body.put("saleId", saleId.toString());
        if (paymentMethod != null) body.put("paymentMethod", paymentMethod);
        return body;
    }

    private void stockOut(Shop shop, UUID productId, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response = send(shop, HttpMethod.POST,
                "/api/products/" + productId + "/stock/stock-out", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void stockIn(Shop shop, UUID productId, int quantity) {
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + productId + "/stock/stock-in",
                        Map.of("quantity", quantity, "unitPrice", 100), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> changePayment(Shop shop, UUID saleId, String method, Class<T> type) {
        return send(shop, HttpMethod.PUT, "/api/sales/" + saleId + "/payment", Map.of("paymentMethod", method), type);
    }

    private SaleResponse getSale(Shop shop, UUID saleId) {
        return get(shop, "/api/sales/" + saleId, SaleResponse.class).getBody();
    }

    private <T> ResponseEntity<T> send(Shop shop, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> get(Shop shop, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(shop)), type);
    }

    private UUID product(Shop shop, String name) {
        String sku = name.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, "PIECE", null, null, null), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    /** A second person with Stock in and Stock out only - no inventory management. */
    private Shop cashier(Shop owner) {
        RoleResponse role = restTemplate.exchange("/api/roles", HttpMethod.POST,
                        new HttpEntity<>(new CreateRoleRequest("Cashier " + UUID.randomUUID().toString().substring(0, 8), null,
                                Set.of("STOCK_IN", "STOCK_OUT", "VIEW_PRODUCTS")), auth(owner)),
                        RoleResponse.class)
                .getBody();
        String username = "cashier-" + UUID.randomUUID();
        restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest(username, PASSWORD, role.id(), null, null, null, null, null), auth(owner)),
                String.class);
        return new Shop(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class));
    }

    private Shop shop(String name) {
        String unique = UUID.randomUUID().toString();
        return new Shop(restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class));
    }

    private static HttpHeaders auth(Shop shop) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.login().tokens().accessToken());
        return headers;
    }
}
