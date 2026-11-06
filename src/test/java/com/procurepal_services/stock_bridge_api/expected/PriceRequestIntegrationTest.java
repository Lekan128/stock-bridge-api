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
