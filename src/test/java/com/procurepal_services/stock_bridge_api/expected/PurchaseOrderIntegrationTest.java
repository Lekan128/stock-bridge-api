package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.math.BigDecimal;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/** PURCHASE_ORDER_PLAN.md phase 1: an expected delivery that has a number and a frozen copy. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PurchaseOrderIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void aPurchaseOrderGetsTheNextNumberForItsCompany() {
        TenantLoginResponse tenant = signup("Numbering Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "PO-RICE");

        ExpectedDeliveryResponse first = order(tenant, tony.id(), rice.id(), "10", "2099-11-12");
        ExpectedDeliveryResponse second = order(tenant, tony.id(), rice.id(), "5", null);

        assertThat(first.poNumber()).isEqualTo("PO-0001");
        assertThat(second.poNumber()).isEqualTo("PO-0002");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.title()).isEqualTo("PO-0001, Tony Stores, due 12 Nov");
        assertThat(second.title()).isEqualTo("PO-0002, Tony Stores");
    }

    @Test
    void eachCompanyCountsFromOneAndNeverSeesAnothersNumbers() {
        TenantLoginResponse a = signup("Company A");
        TenantLoginResponse b = signup("Company B");
        ExpectedDeliveryResponse fromA = order(a, vendor(a, "Tony").id(), product(a, "Rice", "PO-A").id(), "1", null);
        ExpectedDeliveryResponse fromB = order(b, vendor(b, "Ade").id(), product(b, "Rice", "PO-B").id(), "1", null);

        assertThat(fromA.poNumber()).isEqualTo("PO-0001");
        assertThat(fromB.poNumber()).isEqualTo("PO-0001");
    }

    @Test
    void aPurchaseOrderAsksForNoPricesAndShowsNoTotal() {
        TenantLoginResponse tenant = signup("No Prices Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "PO-NP");

        ExpectedDeliveryResponse created = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("20"), new BigDecimal("999"))),
                null, true));

        assertThat(created.total()).isNull();
        assertThat(created.lines()).allSatisfy(line -> assertThat(line.price()).isNull());
    }

    @Test
    void aPurchaseOrderNeedsASupplier() {
        TenantLoginResponse tenant = signup("No Supplier Co");
        ProductResponse rice = product(tenant, "Rice", "PO-NS");

        ResponseEntity<String> response = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(
                        null, null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null)),
                        null, true), json(tenant)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Pick who you are ordering from.");
    }

    @Test
    void anExpectationWrittenDownTheOldWayStaysUnnumbered() {
        TenantLoginResponse tenant = signup("Old Way Co");
        ProductResponse rice = product(tenant, "Rice", "PO-OLD");

        ExpectedDeliveryResponse created = create(tenant, new ExpectedDeliveryRequest(
                null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("3"), new BigDecimal("100")))));

        assertThat(created.poNumber()).isNull();
        assertThat(created.title()).isEqualTo("Expected delivery");
        // The old path keeps its price - only a purchase order drops it.
        assertThat(created.total()).isEqualByComparingTo("300");
    }

    @Test
    void whatTheSupplierIsShownIsFrozenOntoTheOrder() {
        TenantLoginResponse tenant = signup("Frozen Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "PO-FZ");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), "10", "2099-11-12");

        String json = jdbc.queryForObject(
                "SELECT snapshot FROM expected_deliveries WHERE id = ?", String.class, created.id());
        JsonNode snapshot = jsonMapper.readTree(json);
        assertThat(snapshot.get("poNumber").asText()).isEqualTo("PO-0001");
        assertThat(snapshot.get("supplier").get("name").asText()).isEqualTo("Tony Stores");
        assertThat(snapshot.get("supplier").get("phone").asText()).isEqualTo("08030000000");
        assertThat(snapshot.get("buyer").get("name").asText()).startsWith("Frozen Co");
        assertThat(snapshot.get("neededBy").asText()).isEqualTo("2099-11-12");
        assertThat(snapshot.get("lines")).hasSize(1);
        assertThat(snapshot.get("lines").get(0).get("productName").asText()).isEqualTo("Rice");
        assertThat(snapshot.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("10");
        // No prices anywhere on what the supplier sees.
        assertThat(json).doesNotContain("price").doesNotContain("total");
    }

    @Test
    void thePdfOpensInlineInTheBrowserAndIsDrawnFromTheFrozenOrder() throws Exception {
        TenantLoginResponse tenant = signup("Pdf Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "PO-PDF");
        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), "10", "2099-11-12");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/expected-deliveries/" + created.id() + "/pdf", HttpMethod.GET,
                new HttpEntity<>(json(tenant)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("PO-0001 Tony Stores.pdf");
        assertThat(response.getBody()).startsWith("%PDF".getBytes());
    }

    @Test
    void anExpectationThatWasOnlyWrittenDownHasNoPdf() {
        TenantLoginResponse tenant = signup("No Pdf Co");
        ProductResponse rice = product(tenant, "Rice", "PO-NOPDF");
        ExpectedDeliveryResponse oldWay = create(tenant, new ExpectedDeliveryRequest(
                null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null))));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/expected-deliveries/" + oldWay.id() + "/pdf", HttpMethod.GET,
                new HttpEntity<>(json(tenant)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherCompanyCannotOpenYourPdf() {
        TenantLoginResponse mine = signup("Mine Co");
        TenantLoginResponse theirs = signup("Theirs Co");
        ExpectedDeliveryResponse created =
                order(mine, vendor(mine, "Tony").id(), product(mine, "Rice", "PO-MINE").id(), "1", null);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/expected-deliveries/" + created.id() + "/pdf", HttpMethod.GET,
                new HttpEntity<>(json(theirs)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPurchaseOrderCannotBeNeededOnADayThatHasPassedButTodayIsFine() {
        TenantLoginResponse tenant = signup("Dates Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "PO-DATE");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos"));

        ResponseEntity<String> past = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(
                        tony.id(), today.minusDays(1).toString(), null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null)), null, true),
                        json(tenant)),
                String.class);
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(past.getBody()).contains("Pick today or a day after today.");

        ExpectedDeliveryResponse todayOrder = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), today.toString(), null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null)), null, true));
        assertThat(todayOrder.poNumber()).isEqualTo("PO-0001");
    }

    @Test
    void anExpectationWrittenDownAfterTheFactKeepsItsPastDate() {
        TenantLoginResponse tenant = signup("Overdue Co");
        ProductResponse rice = product(tenant, "Rice", "PO-OVER");

        ExpectedDeliveryResponse created = create(tenant, new ExpectedDeliveryRequest(
                null, "2026-01-05", null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null))));

        assertThat(created.expectedDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 5));
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private ExpectedDeliveryResponse order(
            TenantLoginResponse tenant, UUID vendorId, UUID productId, String quantity, String neededBy) {
        return create(tenant, new ExpectedDeliveryRequest(
                vendorId, neededBy, null, null,
                List.of(new ExpectedDeliveryRequest.Line(productId, "KG", new BigDecimal(quantity), null)),
                null, true));
    }

    private ExpectedDeliveryResponse create(TenantLoginResponse tenant, ExpectedDeliveryRequest request) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
