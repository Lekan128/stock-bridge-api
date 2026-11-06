package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Price requests to several suppliers (PURCHASE_ORDER_PLAN.md section 11.3, V80). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PriceRequestIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void suppliersReplyFromTheirLinkOrByPhoneAndTheCheapestForEachItemBecomesTwoOrders() {
        TenantLoginResponse owner = signup("Quote Co");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        CompanyVendorResponse mama = vendor(owner, "Mama Put");
        ProductResponse rice = product(owner, "Rice", "PR-RICE");
        ProductResponse oil = product(owner, "Oil", "PR-OIL");

        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/price-requests", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "lines", List.of(Map.of("productId", rice.id(), "unit", "KG", "quantity", 20),
                                Map.of("productId", oil.id(), "unit", "KG", "quantity", 10)),
                        "vendorIds", List.of(tony.id(), mama.id()),
                        "replyBy", "2099-11-07"), json(owner)), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode request = created.getBody();
        String id = request.get("id").asText();
        assertThat(request.get("rfqNumber").asText()).isEqualTo("RFQ-0001");
        assertThat(request.get("suppliers")).hasSize(2);
        JsonNode lines = request.get("lines");
        String riceLine = lines.get(0).get("id").asText();
        String oilLine = lines.get(1).get("id").asText();
        String tonyRow = supplierRow(request, "Tony Stores");
        String mamaRow = supplierRow(request, "Mama Put");
        assertThat(supplierOf(request, "Tony Stores").get("message").asText()).contains("Rice: 20 × ").contains("RFQ-0001");

        // Tony opens his link (no login) and sends his prices with a remark.
        String token = jdbc.queryForObject("SELECT token FROM price_request_suppliers WHERE id = ?::uuid", String.class, tonyRow);
        JsonNode page = restTemplate.getForObject("/api/public/price-requests/" + token, JsonNode.class);
        assertThat(page.get("supplierName").asText()).isEqualTo("Tony Stores");
        assertThat(page.get("state").asText()).isEqualTo("OPEN");
        assertThat(page.has("reply")).isFalse();
        restTemplate.postForEntity("/api/public/price-requests/" + token + "/opened", null, Void.class);
        ResponseEntity<JsonNode> replied = restTemplate.exchange("/api/public/price-requests/" + token + "/reply",
                HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "lines", List.of(Map.of("lineId", riceLine, "price", 900, "cannotSupply", false),
                                Map.of("lineId", oilLine, "price", 380, "cannotSupply", false)),
                        "deliveryCharge", 500, "remark", "Oil price holds till Friday", "declined", false), jsonOnly()),
                JsonNode.class);
        assertThat(replied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replied.getBody().get("reply").get("total").decimalValue()).isEqualByComparingTo("22300");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'PRICE_REPLY' AND link LIKE ?",
                Integer.class, "%" + id)).as("the buyer is told").isGreaterThanOrEqualTo(1);

        // Mama phoned: the buyer types her prices in.
        ResponseEntity<JsonNode> typed = restTemplate.exchange("/api/price-requests/" + id + "/suppliers/" + mamaRow + "/reply",
                HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "lines", List.of(Map.of("lineId", riceLine, "price", 880, "cannotSupply", false),
                                Map.of("lineId", oilLine, "price", 395, "cannotSupply", false)),
                        "deliveryCharge", 800, "declined", false), json(owner)), JsonNode.class);
        assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode compared = typed.getBody();
        assertThat(supplierOf(compared, "Tony Stores").get("status").asText()).isEqualTo("REPLIED");
        assertThat(supplierOf(compared, "Tony Stores").get("replySource").asText()).isEqualTo("LINK");
        assertThat(supplierOf(compared, "Tony Stores").get("remark").asText()).isEqualTo("Oil price holds till Friday");
        assertThat(supplierOf(compared, "Mama Put").get("replySource").asText()).isEqualTo("TYPED");
        assertThat(compared.get("lowestByLine").get(riceLine).asText()).isEqualTo(mamaRow);
        assertThat(compared.get("lowestByLine").get(oilLine).asText()).isEqualTo(tonyRow);
        // Cheapest for each: rice 20 × 880 + oil 10 × 380 + both delivery charges.
        JsonNode cheapest = compared.get("options").get(0);
        assertThat(cheapest.get("kind").asText()).isEqualTo("CHEAPEST");
        assertThat(cheapest.get("total").decimalValue()).isEqualByComparingTo("22700");
        assertThat(cheapest.get("orders").asInt()).isEqualTo(2);

        // Tony changes his prices: still allowed, and the history keeps both.
        restTemplate.exchange("/api/public/price-requests/" + token + "/reply", HttpMethod.PUT, new HttpEntity<>(Map.of(
                "lines", List.of(Map.of("lineId", riceLine, "price", 890, "cannotSupply", false),
                        Map.of("lineId", oilLine, "price", 380, "cannotSupply", false)),
                "deliveryCharge", 500, "declined", false), jsonOnly()), JsonNode.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_request_reply_history WHERE supplier_row_id = ?::uuid",
                Integer.class, tonyRow)).isEqualTo(2);

        ResponseEntity<JsonNode> chosen = restTemplate.exchange("/api/price-requests/" + id + "/choose", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "CHEAPEST"), json(owner)), JsonNode.class);
        assertThat(chosen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chosen.getBody().get("orderIds")).hasSize(2);
        JsonNode after = get(owner, "/api/price-requests/" + id);
        assertThat(after.get("status").asText()).isEqualTo("DONE");
        String tonyOrder = supplierOf(after, "Tony Stores").get("orderId").asText();
        JsonNode order = get(owner, "/api/expected-deliveries/" + tonyOrder);
        assertThat(order.get("lines")).hasSize(1);
        assertThat(order.get("lines").get(0).get("price").decimalValue()).isEqualByComparingTo("380");
        assertThat(order.get("deliveryCharge").decimalValue()).isEqualByComparingTo("500");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("4300");

        // Tony's link now shows his order; replying again is refused.
        assertThat(restTemplate.getForObject("/api/public/price-requests/" + token, JsonNode.class).get("state").asText())
                .isEqualTo("ORDERED");
        assertThat(restTemplate.exchange("/api/public/price-requests/" + token + "/reply", HttpMethod.PUT,
                new HttpEntity<>(Map.of("lines", List.of(), "declined", true), jsonOnly()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(restTemplate.getForEntity("/api/public/price-requests/not-a-real-token-at-all", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Replies close at the end of the Reply by day (section 12.1): after it, the supplier's link shows what they sent
     * and takes no more; the buyer can still type a reply heard on the phone, and Give more time reopens it.
     */
    @Test
    void repliesCloseOnTheReplyByDayAndGiveMoreTimeReopensThem() {
        TenantLoginResponse owner = signup("Close Co");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        ProductResponse rice = product(owner, "Rice", "PC-RICE");
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).toString();
        String yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).minusDays(1).toString();

        assertThat(restTemplate.exchange("/api/price-requests", HttpMethod.POST, new HttpEntity<>(Map.of(
                "lines", List.of(Map.of("productId", rice.id(), "unit", "KG", "quantity", 5)),
                "vendorIds", List.of(tony.id())), json(owner)), String.class).getBody()).contains("reply by");
        assertThat(restTemplate.exchange("/api/price-requests", HttpMethod.POST, new HttpEntity<>(Map.of(
                "lines", List.of(Map.of("productId", rice.id(), "unit", "KG", "quantity", 5)),
                "vendorIds", List.of(tony.id()), "replyBy", yesterday), json(owner)), String.class).getBody())
                .contains("can't be in the past");
        JsonNode request = restTemplate.exchange("/api/price-requests", HttpMethod.POST, new HttpEntity<>(Map.of(
                "lines", List.of(Map.of("productId", rice.id(), "unit", "KG", "quantity", 5)),
                "vendorIds", List.of(tony.id()), "replyBy", today), json(owner)), JsonNode.class).getBody();
        String id = request.get("id").asText();
        String line = request.get("lines").get(0).get("id").asText();
        String tonyRow = supplierRow(request, "Tony Stores");
        assertThat(request.get("repliesClosed").asBoolean()).as("open all of the Reply by day").isFalse();
        assertThat(supplierOf(request, "Tony Stores").get("message").asText()).contains("Prices can't be changed after that.");
        String token = jdbc.queryForObject("SELECT token FROM price_request_suppliers WHERE id = ?::uuid", String.class, tonyRow);
        Map<String, Object> reply = Map.of("lines", List.of(Map.of("lineId", line, "price", 900, "cannotSupply", false)),
                "declined", false);
        assertThat(restTemplate.exchange("/api/public/price-requests/" + token + "/reply", HttpMethod.PUT,
                new HttpEntity<>(reply, jsonOnly()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The day passes.
        jdbc.update("UPDATE price_requests SET reply_by = ?::date WHERE id = ?::uuid", yesterday, id);
        JsonNode page = restTemplate.getForObject("/api/public/price-requests/" + token, JsonNode.class);
        assertThat(page.get("repliesClosed").asBoolean()).isTrue();
        assertThat(page.get("reply").get("total").decimalValue()).as("what they sent still shows").isEqualByComparingTo("4500");
        ResponseEntity<String> late = restTemplate.exchange("/api/public/price-requests/" + token + "/reply", HttpMethod.PUT,
                new HttpEntity<>(reply, jsonOnly()), String.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).contains("Replies closed on").contains("call Close Co");
        assertThat(get(owner, "/api/price-requests/" + id).get("repliesClosed").asBoolean()).isTrue();
        assertThat(get(owner, "/api/price-requests?status=OPEN").get(0).get("repliesClosed").asBoolean()).isTrue();
        // The buyer can still type what Tony said on the phone.
        assertThat(restTemplate.exchange("/api/price-requests/" + id + "/suppliers/" + tonyRow + "/reply", HttpMethod.PUT,
                new HttpEntity<>(reply, json(owner)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Give more time: replies open again until the new day.
        assertThat(restTemplate.exchange("/api/price-requests/" + id + "/reply-by", HttpMethod.POST,
                new HttpEntity<>(Map.of("replyBy", yesterday), json(owner)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode reopened = restTemplate.exchange("/api/price-requests/" + id + "/reply-by", HttpMethod.POST,
                new HttpEntity<>(Map.of("replyBy", today), json(owner)), JsonNode.class).getBody();
        assertThat(reopened.get("repliesClosed").asBoolean()).isFalse();
        assertThat(restTemplate.exchange("/api/public/price-requests/" + token + "/reply", HttpMethod.PUT,
                new HttpEntity<>(reply, jsonOnly()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The buyer picks the supplier for each item (section 12.2): Tony's dearer rice, Mama's dearer oil, the beans not
     * now. Two orders at those prices, each with its supplier's delivery charge once; the request says what each item
     * went to beside the lowest. Picking a supplier who didn't price an item is refused.
     */
    @Test
    void theBuyerPicksTheSupplierForEachItemEvenTheDearerOne() {
        TenantLoginResponse owner = signup("Pick Co");
        CompanyVendorResponse tony = vendor(owner, "Tony Stores");
        CompanyVendorResponse mama = vendor(owner, "Mama Put");
        ProductResponse rice = product(owner, "Rice", "PK-RICE");
        ProductResponse oil = product(owner, "Oil", "PK-OIL");
        ProductResponse beans = product(owner, "Beans", "PK-BEANS");
        JsonNode request = restTemplate.exchange("/api/price-requests", HttpMethod.POST, new HttpEntity<>(Map.of(
                "lines", List.of(Map.of("productId", rice.id(), "unit", "KG", "quantity", 20),
                        Map.of("productId", oil.id(), "unit", "KG", "quantity", 10),
                        Map.of("productId", beans.id(), "unit", "KG", "quantity", 5)),
                "vendorIds", List.of(tony.id(), mama.id()), "replyBy", "2099-11-07"), json(owner)), JsonNode.class).getBody();
        String id = request.get("id").asText();
        String riceLine = request.get("lines").get(0).get("id").asText();
        String oilLine = request.get("lines").get(1).get("id").asText();
        String beansLine = request.get("lines").get(2).get("id").asText();
        String tonyRow = supplierRow(request, "Tony Stores");
        String mamaRow = supplierRow(request, "Mama Put");
        restTemplate.exchange("/api/price-requests/" + id + "/suppliers/" + tonyRow + "/reply", HttpMethod.PUT,
                new HttpEntity<>(Map.of("lines", List.of(Map.of("lineId", riceLine, "price", 900, "cannotSupply", false),
                        Map.of("lineId", oilLine, "price", 380, "cannotSupply", false),
                        Map.of("lineId", beansLine, "cannotSupply", true)), "deliveryCharge", 500, "declined", false),
                        json(owner)), String.class);
        restTemplate.exchange("/api/price-requests/" + id + "/suppliers/" + mamaRow + "/reply", HttpMethod.PUT,
                new HttpEntity<>(Map.of("lines", List.of(Map.of("lineId", riceLine, "price", 880, "cannotSupply", false),
                        Map.of("lineId", oilLine, "price", 395, "cannotSupply", false),
                        Map.of("lineId", beansLine, "price", 600, "cannotSupply", false)), "deliveryCharge", 800,
                        "declined", false), json(owner)), String.class);

        ResponseEntity<String> notPriced = restTemplate.exchange("/api/price-requests/" + id + "/choose", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "PICKED", "picks", List.of(Map.of("lineId", beansLine, "supplierId", tonyRow))),
                        json(owner)), String.class);
        assertThat(notPriced.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notPriced.getBody()).contains("Beans can only be ordered from a supplier who priced it");

        java.util.Map<String, Object> notNow = new java.util.HashMap<>();
        notNow.put("lineId", beansLine);
        notNow.put("supplierId", null);
        ResponseEntity<JsonNode> chosen = restTemplate.exchange("/api/price-requests/" + id + "/choose", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "PICKED", "picks", List.of(
                        Map.of("lineId", riceLine, "supplierId", tonyRow),
                        Map.of("lineId", oilLine, "supplierId", mamaRow), notNow)), json(owner)), JsonNode.class);
        assertThat(chosen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chosen.getBody().get("orderIds")).hasSize(2);
        JsonNode after = get(owner, "/api/price-requests/" + id);
        JsonNode tonyOrder = get(owner, "/api/expected-deliveries/" + supplierOf(after, "Tony Stores").get("orderId").asText());
        assertThat(tonyOrder.get("lines")).singleElement().satisfies(orderLine ->
                assertThat(orderLine.get("price").decimalValue()).isEqualByComparingTo("900"));
        assertThat(tonyOrder.get("total").decimalValue()).isEqualByComparingTo("18500");
        JsonNode mamaOrder = get(owner, "/api/expected-deliveries/" + supplierOf(after, "Mama Put").get("orderId").asText());
        assertThat(mamaOrder.get("lines")).singleElement().satisfies(orderLine ->
                assertThat(orderLine.get("price").decimalValue()).isEqualByComparingTo("395"));
        assertThat(mamaOrder.get("total").decimalValue()).isEqualByComparingTo("4750");
        // What each item went to, beside the lowest: the beans were left out.
        JsonNode picked = after.get("picked");
        assertThat(picked).hasSize(2);
        JsonNode ricePick = picked.get(0).get("lineId").asText().equals(riceLine) ? picked.get(0) : picked.get(1);
        assertThat(ricePick.get("vendorName").asText()).isEqualTo("Tony Stores");
        assertThat(ricePick.get("price").decimalValue()).isEqualByComparingTo("900");
        assertThat(ricePick.get("lowestPrice").decimalValue()).isEqualByComparingTo("880");
        assertThat(ricePick.get("lowestVendorName").asText()).isEqualTo("Mama Put");
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private static JsonNode supplierOf(JsonNode request, String name) {
        for (JsonNode supplier : request.get("suppliers")) {
            if (supplier.get("vendorName").asText().equals(name)) {
                return supplier;
            }
        }
        throw new AssertionError("no supplier " + name);
    }

    private static String supplierRow(JsonNode request, String name) {
        return supplierOf(request, name).get("id").asText();
    }

    private JsonNode get(TenantLoginResponse tenant, String path) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(json(tenant)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null, null,
                        null, null, null), json(tenant)),
                CompanyVendorResponse.class).getBody();
    }

    private ProductResponse product(TenantLoginResponse tenant, String name, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, "KG", null, null, null), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class).getBody();
    }

    private static HttpHeaders jsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = jsonOnly();
        headers.setBearerAuth(tenant.tokens().accessToken());
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup", new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }
}
