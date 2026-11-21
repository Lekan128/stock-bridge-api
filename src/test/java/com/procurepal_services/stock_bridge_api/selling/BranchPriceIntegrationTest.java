package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Prices per branch (BRANCH_PRICES_PLAN.md, step 1): the switch, each branch's choice, a product's own prices, D3. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BranchPriceIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyBranchHasPricesItsOwnOrAnotherBranchsAndNoBranchIsSpecial() {
        Shop shop = shop("Branch Price Shop");
        sellingOn(shop);
        String main = branchIds(shop).get(0);
        jdbcTemplate.update("UPDATE clients SET branches_enabled = true WHERE id = (SELECT client_id FROM branches WHERE id = ?)",
                UUID.fromString(main));
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");
        String lekki = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Lekki"), Map.class).getBody().get("id");
        ProductResponse beer = create(shop, Map.of("name", "Heineken", "sku", "HN-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "packagingUnit", "CARTON", "packagingSize", 24,
                "unitPrice", 18000, "unitPriceUnit", "CARTON", "allBranches", true));

        // Off: one price, nothing per branch.
        assertThat(get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody().branchPrices()).isNull();

        // On: every branch shares one copy of today's prices.
        Map<?, ?> settings = send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", true), Map.class).getBody();
        assertThat(settings.get("branchPrices")).isEqualTo(true);
        Object shared = branch(shop, main).get("priceListId");
        assertThat(shared).isNotNull();
        assertThat(branch(shop, ikeja).get("priceListId")).isEqualTo(shared);
        assertThat(branch(shop, lekki).get("priceListId")).isEqualTo(shared);
        assertThat(get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody().branchPrices()).singleElement()
                .satisfies(price -> assertThat(price.unitPriceEntered()).isEqualByComparingTo("18000"));

        // Ikeja takes its own: a copy of what it used, then its alone.
        assertThat(choose(shop, ikeja, "OWN", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Object ikejaList = branch(shop, ikeja).get("priceListId");
        assertThat(ikejaList).isNotEqualTo(shared);
        ProductResponse priced = update(shop, beer.id(), Map.of("unitPriceUnit", "CARTON",
                "branchPrices", List.of(Map.of("branchId", ikeja, "price", 19500)))).getBody();
        assertThat(priced.branchPrices()).hasSize(2);
        assertThat(at(priced, ikejaList).unitPriceEntered()).isEqualByComparingTo("19500");
        assertThat(at(priced, ikejaList).unitPrice()).isEqualByComparingTo("812.50");
        assertThat(at(priced, shared).unitPriceEntered()).isEqualByComparingTo("18000");
        // The product's one price is a copy of the main branch's.
        assertThat(priced.unitPriceEntered()).isEqualByComparingTo("18000");
        int changes = listChanges(beer.id());

        // Sent back unchanged: nothing changes, nothing logged.
        update(shop, beer.id(), Map.of("unitPriceUnit", "CARTON", "branchPrices", List.of(
                Map.of("branchId", main, "price", 18000), Map.of("branchId", ikeja, "price", 19500))));
        assertThat(listChanges(beer.id())).isEqualTo(changes);

        // No branch is special: Head Office can use Ikeja's prices, and the one price follows the main branch.
        assertThat(choose(shop, main, "SAME_AS", ikeja).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody().unitPriceEntered()).isEqualByComparingTo("19500");
        assertThat(choose(shop, main, "OWN", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        update(shop, beer.id(), Map.of("unitPriceUnit", "CARTON", "branchPrices", List.of(Map.of("branchId", main, "price", 18000))));
        // Making another branch main moves no price; the one price copies the new main branch's.
        assertThat(send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/main", Map.of(), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody().unitPriceEntered()).isEqualByComparingTo("19500");
        send(shop, HttpMethod.POST, "/api/branches/" + main + "/main", Map.of(), String.class);

        // Lekki shares Ikeja's, then takes its own (a copy of Ikeja's).
        assertThat(choose(shop, lekki, "SAME_AS", ikeja).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(branch(shop, lekki).get("priceListId")).isEqualTo(ikejaList);
        assertThat(choose(shop, lekki, "OWN", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Object lekkiList = branch(shop, lekki).get("priceListId");
        assertThat(at(get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody(), lekkiList).unitPriceEntered())
                .isEqualByComparingTo("19500");

        // D3: someone at Lekki sets Lekki's price, not Ikeja's nor Head Office's (an older app's one price lands there).
        Shop lekkiStaff = staffAt(shop, lekki);
        assertThat(update(lekkiStaff, beer.id(), Map.of("unitPriceUnit", "CARTON",
                "branchPrices", List.of(Map.of("branchId", lekki, "price", 20000)))).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> notIkeja = updateRaw(lekkiStaff, beer.id(), Map.of("unitPriceUnit", "CARTON",
                "branchPrices", List.of(Map.of("branchId", ikeja, "price", 21000))));
        assertThat(notIkeja.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notIkeja.getBody()).contains("also used at Ikeja");
        assertThat(updateRaw(lekkiStaff, beer.id(), Map.of("unitPrice", 17000, "unitPriceUnit", "CARTON")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // A product added to a branch with no price of its own there: no price there yet.
        ProductResponse fanta = create(shop, Map.of("name", "Fanta", "sku", "FT-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "branchIds", List.of(main), "branchPrices", List.of(Map.of("branchId", main, "price", 600))));
        assertThat(fanta.branchPrices()).hasSize(1);
        assertThat(fanta.unitPrice()).isEqualByComparingTo("600");
        send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products", Map.of("productIds", List.of(fanta.id().toString())), Map.class);
        assertThat(get(shop, "/api/products/" + fanta.id(), ProductResponse.class).getBody().branchPrices())
                .noneMatch(price -> price.priceListId().toString().equals(ikejaList));

        // A blank takes a price off: no price at Ikeja.
        ProductResponse cleared = update(shop, beer.id(), Map.of("unitPriceUnit", "CARTON",
                "branchPrices", List.of(Map.of("branchId", ikeja, "price", "")))).getBody();
        assertThat(cleared.branchPrices()).noneMatch(price -> price.priceListId().toString().equals(ikejaList));

        // Off, choosing Lekki: every branch then uses Lekki's prices. On again: each branch's come back.
        send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", false, "useBranchId", lekki), Map.class);
        ProductResponse off = get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody();
        assertThat(off.branchPrices()).isNull();
        assertThat(off.unitPriceEntered()).isEqualByComparingTo("20000");
        send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", true), Map.class);
        ProductResponse back = get(shop, "/api/products/" + beer.id(), ProductResponse.class).getBody();
        assertThat(at(back, lekkiList).unitPriceEntered()).isEqualByComparingTo("20000");
        assertThat(at(back, branch(shop, main).get("priceListId")).unitPriceEntered()).isEqualByComparingTo("18000");

        // The phone's copy carries them too.
        assertThat(get(shop, "/api/products/sync/snapshot", String.class).getBody()).contains("\"branchPrices\":[{");
    }

    /** The small line on Products (BRANCH_PRICES_PLAN.md 9.2): to fix (no area, no price) and to check (added). */
    @Test
    void theLineSaysWhatToFixAndCheckAtEachBranch() {
        Shop shop = shop("Needs A Look Shop");
        sellingOn(shop);
        send(shop, HttpMethod.PUT, "/api/company/selling/made-here", Map.of("on", true), String.class);
        String main = branchIds(shop).get(0);
        jdbcTemplate.update("UPDATE clients SET branches_enabled = true WHERE id = (SELECT client_id FROM branches WHERE id = ?)",
                UUID.fromString(main));
        send(shop, HttpMethod.POST, "/api/branches/" + main + "/areas",
                Map.of("areas", List.of(Map.of("name", "Kitchen", "keepsStock", true), Map.of("name", "Bar", "keepsStock", true))), List.class);
        ProductResponse rice = create(shop, Map.of("name", "Rice", "sku", "RC-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "branchIds", List.of(main)));
        ProductResponse plate = create(shop, Map.of("name", "Jollof", "sku", "JL-" + UUID.randomUUID().toString().substring(0, 6),
                "madeHere", true, "unitPrice", 7000, "branchIds", List.of(main)));
        assertThat(send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-with", Map.of("madeIn", "Kitchen",
                "ingredients", List.of(Map.of("productId", rice.id().toString(), "quantity", 1))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Ikeja, one area: the plate goes there and brings its rice - rice is to check; one area needs no choosing.
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");
        send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products", Map.of("productIds", List.of(plate.id().toString())), Map.class);
        Map<?, ?> line = look(shop, ikeja);
        assertThat(line.get("toCheck")).isEqualTo(1);
        assertThat(line.get("toFix")).isEqualTo(0);
        assertThat(line.toString()).contains("ADDED_AS_INGREDIENT").contains("aboutName=Jollof");

        // A second area and no Kitchen: to fix. "Add a Kitchen" fixes it; its undo brings it back; choosing an area fixes it.
        send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/areas", Map.of("areas", List.of(Map.of("name", "Bar", "keepsStock", true))), List.class);
        assertThat(look(shop, ikeja).toString()).contains("NO_AREA").contains("madeIn=Kitchen");
        List<Map<String, Object>> kitchen = send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/areas",
                Map.of("areas", List.of(Map.of("name", "Kitchen", "keepsStock", true))), List.class).getBody();
        assertThat(look(shop, ikeja).toString()).doesNotContain("NO_AREA");
        assertThat(send(shop, HttpMethod.DELETE, "/api/areas/" + kitchen.get(0).get("id"), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(look(shop, ikeja).toString()).contains("NO_AREA");
        String bar = jdbcTemplate.queryForObject("SELECT id::text FROM areas WHERE branch_id = ? AND name = 'Bar'", String.class, UUID.fromString(ikeja));
        assertThat(send(shop, HttpMethod.PUT, "/api/products/" + plate.id() + "/made-in/" + ikeja, Map.of("areaId", bar), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(look(shop, ikeja).toString()).doesNotContain("NO_AREA");

        // Looks right: the rice check is done.
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + rice.id() + "/looks-right?branchId=" + ikeja, Map.of(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(look(shop, ikeja).get("toCheck")).isEqualTo(0);

        // Prices per branch: a product priced at Head Office and added to Ikeja after Ikeja took its own - no price there.
        send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", true), Map.class);
        choose(shop, ikeja, "OWN", null);
        ProductResponse fanta = create(shop, Map.of("name", "Fanta", "sku", "FT-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "branchIds", List.of(main), "branchPrices", List.of(Map.of("branchId", main, "price", 600))));
        send(shop, HttpMethod.POST, "/api/branches/" + ikeja + "/products", Map.of("productIds", List.of(fanta.id().toString())), Map.class);
        assertThat(look(shop, ikeja).toString()).contains("NO_PRICE").contains("productName=Fanta");
        // Rice has no price anywhere: never "no price here".
        assertThat(look(shop, ikeja).toString()).doesNotContain("productName=Rice, branchId=" + ikeja + ", branchName=Ikeja, kind=FIX");
        send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(Map.of("productId", fanta.id().toString(), "branchId", ikeja, "price", 650))), Map.class);
        assertThat(look(shop, ikeja).toString()).doesNotContain("NO_PRICE");

        // A one-area branch makes it there: a sale at a branch with only its Store takes the rice from the Store.
        String lekki = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Lekki"), Map.class).getBody().get("id");
        send(shop, HttpMethod.POST, "/api/branches/" + lekki + "/products", Map.of("productIds", List.of(plate.id().toString())), Map.class);
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + plate.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 7000,
                        "paymentMethod", "CASH", "branchId", lekki), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND branch_id = ?",
                Integer.class, rice.id(), UUID.fromString(lekki))).isEqualTo(1);
    }

    private Map<?, ?> look(Shop shop, String branchId) {
        return get(shop, "/api/products/needs-a-look?branchId=" + branchId, Map.class).getBody();
    }

    /** The price sheet (BRANCH_PRICES_PLAN.md §10): cells saved in a batch, at a branch or the one price. */
    @Test
    void thePriceSheetSavesCellsAtABranchOrTheOnePrice() {
        Shop shop = shop("Price Sheet Shop");
        sellingOn(shop);
        String main = branchIds(shop).get(0);
        jdbcTemplate.update("UPDATE clients SET branches_enabled = true WHERE id = (SELECT client_id FROM branches WHERE id = ?)",
                UUID.fromString(main));
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");
        ProductResponse malt = create(shop, Map.of("name", "Malt", "sku", "ML-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "packagingUnit", "CRATE", "packagingSize", 12, "allBranches", true));

        // Off: the one price, typed per crate.
        Map<?, ?> off = send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(
                Map.of("productId", malt.id().toString(), "price", 7200, "unit", "CRATE"))), Map.class).getBody();
        assertThat(off.toString()).contains("unitPriceEntered=7200");
        assertThat(get(shop, "/api/products/" + malt.id(), ProductResponse.class).getBody().unitPrice()).isEqualByComparingTo("600");

        // On, Ikeja on its own: two cells in one go.
        send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", true), Map.class);
        choose(shop, ikeja, "OWN", null);
        ResponseEntity<Map> both = send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(
                Map.of("productId", malt.id().toString(), "branchId", main, "price", 7500, "unit", "CRATE"),
                Map.of("productId", malt.id().toString(), "branchId", ikeja, "price", 8000, "unit", "CRATE"))), Map.class);
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductResponse priced = get(shop, "/api/products/" + malt.id(), ProductResponse.class).getBody();
        assertThat(at(priced, branch(shop, ikeja).get("priceListId")).unitPriceEntered()).isEqualByComparingTo("8000");
        assertThat(at(priced, branch(shop, main).get("priceListId")).unitPriceEntered()).isEqualByComparingTo("7500");
        int changes = listChanges(malt.id());
        // The same again: nothing logged. A blank: no price at Ikeja.
        send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(
                Map.of("productId", malt.id().toString(), "branchId", ikeja, "price", 8000, "unit", "CRATE"))), Map.class);
        assertThat(listChanges(malt.id())).isEqualTo(changes);
        Map<String, Object> blank = new HashMap<>(Map.of("productId", malt.id().toString(), "branchId", ikeja));
        send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(blank)), Map.class);
        assertThat(get(shop, "/api/products/" + malt.id(), ProductResponse.class).getBody().branchPrices()).hasSize(1);
        // On, a cell must say its branch.
        assertThat(send(shop, HttpMethod.PUT, "/api/prices", Map.of("cells", List.of(
                Map.of("productId", malt.id().toString(), "price", 1))), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** Selling at the branch's price (BRANCH_PRICES_PLAN.md step 4): the usual price and stock worth are the branch's. */
    @Test
    void aSaleAndStockWorthAtABranchUseThatBranchsPrice() {
        Shop shop = shop("Branch Sale Shop");
        sellingOn(shop);
        String main = branchIds(shop).get(0);
        jdbcTemplate.update("UPDATE clients SET branches_enabled = true WHERE id = (SELECT client_id FROM branches WHERE id = ?)",
                UUID.fromString(main));
        String ikeja = (String) send(shop, HttpMethod.POST, "/api/branches", Map.of("name", "Ikeja"), Map.class).getBody().get("id");
        ProductResponse coke = create(shop, Map.of("name", "Coke", "sku", "CK-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "unitPrice", 500, "allBranches", true));
        send(shop, HttpMethod.PUT, "/api/company/selling/branch-prices", Map.of("on", true), Map.class);
        choose(shop, ikeja, "OWN", null);
        update(shop, coke.id(), Map.of("branchPrices", List.of(Map.of("branchId", ikeja, "price", 600))));
        for (String branchId : List.of(main, ikeja)) {
            assertThat(send(shop, HttpMethod.POST, "/api/products/" + coke.id() + "/stock/stock-in",
                    Map.of("quantity", 10, "unitPrice", 300, "branchId", branchId), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        }

        // A sale at Ikeja at 550: below Ikeja's 600, so it's measured against 600, not Head Office's 500.
        assertThat(send(shop, HttpMethod.POST, "/api/products/" + coke.id() + "/stock/stock-out",
                Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID().toString(), "lineTotal", 550,
                        "paymentMethod", "CASH", "branchId", ikeja), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usual_price_at_time FROM stock_movements WHERE product_id = ? AND out_reason = 'SALE'", java.math.BigDecimal.class,
                coke.id())).isEqualByComparingTo("600");

        // Stock worth at each branch's prices: Ikeja's 9 at 600, Head Office's 10 at 500.
        Map<?, ?> atIkeja = get(shop, "/api/analytics/stock-worth?branchId=" + ikeja, Map.class).getBody();
        assertThat(new java.math.BigDecimal(atIkeja.get("valueAtSellingPrice").toString())).isEqualByComparingTo("5400");
        Map<?, ?> atMain = get(shop, "/api/analytics/stock-worth?branchId=" + main, Map.class).getBody();
        assertThat(new java.math.BigDecimal(atMain.get("valueAtSellingPrice").toString())).isEqualByComparingTo("5000");
    }

    private static com.procurepal_services.stock_bridge_api.selling.BranchPriceService.ListPrice at(ProductResponse product, Object list) {
        return product.branchPrices().stream().filter(price -> price.priceListId().toString().equals(list)).findFirst().orElseThrow();
    }

    private int listChanges(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_price_changes WHERE product_id = ? AND price_list_id IS NOT NULL",
                Integer.class, productId);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private ResponseEntity<Void> choose(Shop shop, String branchId, String uses, String sameAs) {
        Map<String, Object> body = new HashMap<>();
        body.put("uses", uses);
        if (sameAs != null) body.put("sameAsBranchId", sameAs);
        return send(shop, HttpMethod.PUT, "/api/branches/" + branchId + "/prices", body, Void.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> branch(Shop shop, String id) {
        return ((List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches")).stream()
                .filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> branchIds(Shop shop) {
        List<String> ids = new ArrayList<>();
        ((List<Map<String, Object>>) get(shop, "/api/branches", Map.class).getBody().get("branches"))
                .forEach(row -> ids.add((String) row.get("id")));
        return ids;
    }

    private Shop staffAt(Shop owner, String branchId) {
        RoleResponse role = restTemplate.exchange("/api/roles", HttpMethod.POST,
                        new HttpEntity<>(new CreateRoleRequest("Pricer " + UUID.randomUUID().toString().substring(0, 8), null,
                                Set.of("VIEW_PRODUCTS", "MANAGE_PRODUCTS", "CHANGE_SELLING_PRICES")), auth(owner)),
                        RoleResponse.class)
                .getBody();
        String username = "pricer-" + UUID.randomUUID();
        Map<String, Object> user = new HashMap<>();
        user.put("username", username);
        user.put("password", PASSWORD);
        user.put("roleId", role.id());
        user.put("branchIds", List.of(branchId));
        assertThat(restTemplate.exchange("/api/users", HttpMethod.POST, new HttpEntity<>(user, json(owner)), String.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
        return new Shop(restTemplate.postForObject("/api/auth/login",
                new LoginRequest(owner.login().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class));
    }

    private ProductResponse create(Shop shop, Map<String, Object> fields) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange("/api/products", HttpMethod.POST,
                multipart(shop, fields), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<ProductResponse> update(Shop shop, UUID id, Map<String, Object> fields) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange("/api/products/" + id, HttpMethod.PUT,
                multipart(shop, fields), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<String> updateRaw(Shop shop, UUID id, Map<String, Object> fields) {
        return restTemplate.exchange("/api/products/" + id, HttpMethod.PUT, multipart(shop, fields), String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(Shop shop, Map<String, Object> fields) {
        // A blank price is sent as null: the form's "use the main price".
        Map<String, Object> body = new HashMap<>(fields);
        if (body.get("branchPrices") instanceof List<?> rows) {
            body.put("branchPrices", rows.stream().map(row -> {
                Map<String, Object> copy = new HashMap<>((Map<String, Object>) row);
                if ("".equals(copy.get("price"))) copy.put("price", null);
                return copy;
            }).toList());
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        parts.add("product", new HttpEntity<>(body, part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }

    private void sellingOn(Shop shop) {
        assertThat(send(shop, HttpMethod.PUT, "/api/company/selling", Map.of("sellsStock", true, "source", "OWNER"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> send(Shop shop, HttpMethod method, String path, Object body, Class<T> type) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, json(shop)), type);
    }

    private <T> ResponseEntity<T> get(Shop shop, String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(shop)), type);
    }

    private Shop shop(String name) {
        String unique = UUID.randomUUID().toString();
        return new Shop(restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class));
    }

    private static HttpHeaders json(Shop shop) {
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders auth(Shop shop) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.login().tokens().accessToken());
        return headers;
    }

    private record Shop(TenantLoginResponse login) {
    }
}
