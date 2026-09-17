package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.DeliveryRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * BULK_IMPORT_CX_PLAN.md task 3.1 - what a company is waiting for from an off-platform supplier,
 * and what happens when it turns up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ExpectedDeliveryIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void anExpectationNamesItsSupplierItsDateAndWhatIsStillOwed() {
        TenantLoginResponse tenant = signup("Expecting Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice (Mama Gold)", "EX-RICE", "KG", "BAG", new BigDecimal("50"));
        ProductResponse onion = product(tenant, "Onion", "EX-ONION", "KG", "BASKET", new BigDecimal("30"));

        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), "2026-09-22", "PO-9", "Driver will call",
                List.of(
                        new ExpectedDeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("10"), new BigDecimal("42000")),
                        new ExpectedDeliveryRequest.Line(onion.id(), "BASKET:30", new BigDecimal("5"), new BigDecimal("29000")))));

        assertThat(expected.status().name()).isEqualTo("OPEN");
        assertThat(expected.title()).isEqualTo("Tony Stores, due 22 Sep");
        assertThat(expected.vendorName()).isEqualTo("Tony Stores");
        assertThat(expected.reference()).isEqualTo("PO-9");
        assertThat(expected.outstandingLines()).isEqualTo(2);
        assertThat(expected.receivable()).isTrue();
        // 10 bags at N42,000 and 5 baskets at N29,000.
        assertThat(expected.total()).isEqualByComparingTo("565000");
        assertThat(expected.lines()).extracting(ExpectedDeliveryResponse.Line::comesIn)
                .containsExactly("Bag · 50 kg", "Basket · 30 kg");
        assertThat(expected.lines()).allSatisfy(line -> {
            assertThat(line.receivedQuantity()).isEqualByComparingTo("0");
            assertThat(line.outstanding()).isEqualByComparingTo(line.quantity());
        });
    }

    /**
     * A promise is not paid-for marketplace stock. It shows as "coming" on the product, in the
     * unit it was ordered in, and never touches quantityOnHand or incomingQuantity - see
     * {@code ExpectedDelivery}'s note on why those two must not be added together.
     */
    @Test
    void whatIsComingShowsOnTheProductWithoutTouchingStockOrMarketplaceIncoming() {
        TenantLoginResponse tenant = signup("Coming Soon Co");
        ProductResponse rice = product(tenant, "Rice", "CS-RICE", "KG", "BAG", new BigDecimal("50"));

        assertThat(productById(tenant, rice.id()).expectedQuantity()).as("nothing ordered yet").isNull();

        create(tenant, new ExpectedDeliveryRequest(null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("10"), null))));

        ProductResponse after = productById(tenant, rice.id());
        assertThat(after.expectedQuantity()).as("10 bags, in the unit it was ordered in").isEqualByComparingTo("10");
        assertThat(after.quantityOnHand()).isZero();
        assertThat(after.incomingQuantity()).as("that number belongs to paid marketplace orders").isZero();
    }

    @Test
    void receivingItInFullCreditsEveryLineAndClosesTheRecord() {
        TenantLoginResponse tenant = signup("Receive In Full Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RF-RICE", "KG", "BAG", new BigDecimal("50"));

        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("10"), new BigDecimal("42000")))));

        receive(tenant, expected.id(), tony.id(), rice.id(), "BAG:50", new BigDecimal("10"));

        ExpectedDeliveryResponse after = expectation(tenant, expected.id());
        assertThat(after.status().name()).isEqualTo("RECEIVED");
        assertThat(after.outstandingLines()).isZero();
        assertThat(after.receivable()).isFalse();
        assertThat(after.lines().get(0).receivedQuantity()).isEqualByComparingTo("10");
        assertThat(after.lines().get(0).outstanding()).isEqualByComparingTo("0");

        // The stock itself went through the ordinary stock-in: 10 bags of 50 kg.
        assertThat(productById(tenant, rice.id()).quantityOnHand()).isEqualTo(500);
        assertThat(productById(tenant, rice.id()).expectedQuantity()).as("nothing owed any more").isNull();
    }

    @Test
    void ashortDeliveryLeavesTheRestStillExpected() {
        TenantLoginResponse tenant = signup("Short Delivery Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "SD-RICE", "KG", "BAG", new BigDecimal("50"));

        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("10"), new BigDecimal("42000")))));

        receive(tenant, expected.id(), tony.id(), rice.id(), "BAG:50", new BigDecimal("4"));

        ExpectedDeliveryResponse after = expectation(tenant, expected.id());
        assertThat(after.status().name()).as("six bags short is still open, not a fourth status").isEqualTo("OPEN");
        assertThat(after.outstandingLines()).isEqualTo(1);
        assertThat(after.lines().get(0).receivedQuantity()).isEqualByComparingTo("4");
        assertThat(after.lines().get(0).outstanding()).isEqualByComparingTo("6");
        assertThat(productById(tenant, rice.id()).expectedQuantity()).isEqualByComparingTo("6");
        assertThat(productById(tenant, rice.id()).quantityOnHand()).isEqualTo(200);
    }

    @Test
    void undoingTheReceiptPutsItBackOnTheExpectedList() {
        TenantLoginResponse tenant = signup("Undo Receipt Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "UR-RICE", "KG", "BAG", new BigDecimal("50"));

        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("10"), new BigDecimal("42000")))));

        UUID sessionId = receive(tenant, expected.id(), tony.id(), rice.id(), "BAG:50", new BigDecimal("10"));
        assertThat(expectation(tenant, expected.id()).status().name()).isEqualTo("RECEIVED");

        ResponseEntity<String> undo = restTemplate.exchange("/api/imports/" + sessionId + "/undo",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(undo.getStatusCode()).as(undo.getBody()).isEqualTo(HttpStatus.OK);

        ExpectedDeliveryResponse after = expectation(tenant, expected.id());
        assertThat(after.status().name()).as("un-received, so it is owed again").isEqualTo("OPEN");
        assertThat(after.lines().get(0).receivedQuantity()).isEqualByComparingTo("0");
        assertThat(productById(tenant, rice.id()).quantityOnHand()).isZero();
        assertThat(productById(tenant, rice.id()).expectedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void aUnitThisProductIsNotBoughtInIsRefusedByNamingIt() {
        TenantLoginResponse tenant = signup("Wrong Unit Co");
        ProductResponse rice = product(tenant, "Rice", "WU-RICE", "KG", "BAG", new BigDecimal("50"));

        ResponseEntity<String> response = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(null, null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "DRUM:200", BigDecimal.TEN, null))),
                        json(tenant)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Rice").contains("Pick one of the ways you buy it");
    }

    @Test
    void anEmptyExpectationIsRefused() {
        TenantLoginResponse tenant = signup("Empty Expectation Co");

        ResponseEntity<String> response = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(null, null, null, null, List.of()), json(tenant)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * A date already past is allowed on purpose: a delivery that should have come last Tuesday and
     * has not is exactly what the list exists to show.
     */
    @Test
    void anOverdueExpectationIsPerfectlyLegal() {
        TenantLoginResponse tenant = signup("Overdue Co");
        ProductResponse rice = product(tenant, "Rice", "OD-RICE", "KG", null, null);

        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                null, LocalDate.now().minusDays(9).toString(), null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("40"), null))));

        assertThat(expected.status().name()).isEqualTo("OPEN");
        assertThat(expected.expectedDate()).isEqualTo(LocalDate.now().minusDays(9));
        assertThat(expected.total()).as("no prices were agreed, so there is no total to state").isNull();
    }

    @Test
    void cancellingKeepsTheRecordAndStopsItBeingReceivable() {
        TenantLoginResponse tenant = signup("Cancelled Expectation Co");
        ProductResponse rice = product(tenant, "Rice", "CX-RICE", "KG", null, null);
        ExpectedDeliveryResponse expected = create(tenant, new ExpectedDeliveryRequest(
                null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("40"), null))));

        ExpectedDeliveryResponse cancelled = restTemplate.exchange(
                "/api/expected-deliveries/" + expected.id() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();

        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(cancelled.receivable()).isFalse();
        assertThat(productById(tenant, rice.id()).expectedQuantity()).as("cancelled is not coming").isNull();
        assertThat(expectation(tenant, expected.id()).lines()).as("what you never got is still worth keeping").hasSize(1);
    }

    @Test
    void theListFiltersToWhatIsStillOpen() {
        TenantLoginResponse tenant = signup("Expected List Co");
        ProductResponse rice = product(tenant, "Rice", "EL-RICE", "KG", null, null);
        ExpectedDeliveryResponse first = create(tenant, new ExpectedDeliveryRequest(null, null, "FIRST", null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("10"), null))));
        create(tenant, new ExpectedDeliveryRequest(null, null, "SECOND", null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("20"), null))));
        restTemplate.exchange("/api/expected-deliveries/" + first.id() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), String.class);

        assertThat(references(tenant, "?status=OPEN")).containsExactly("SECOND");
        assertThat(references(tenant, "")).containsExactly("SECOND", "FIRST");
    }

    @Test
    void anotherCompanyCannotSeeOrCancelYours() {
        TenantLoginResponse mine = signup("Mine Expectation Co");
        TenantLoginResponse theirs = signup("Theirs Expectation Co");
        ProductResponse rice = product(mine, "Rice", "PX-RICE", "KG", null, null);
        ExpectedDeliveryResponse expected = create(mine, new ExpectedDeliveryRequest(null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("10"), null))));

        assertThat(restTemplate.exchange("/api/expected-deliveries/" + expected.id(), HttpMethod.GET,
                new HttpEntity<>(json(theirs)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/expected-deliveries/" + expected.id() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(json(theirs)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(references(theirs, "")).isEmpty();
    }

    // ------------------------------------------------------------------------------ helpers ----

    /** Records the arrival through the ordinary delivery endpoint, and returns the import's id. */
    private UUID receive(
            TenantLoginResponse tenant, UUID expectedId, UUID vendorId, UUID productId, String unit,
            BigDecimal quantity) {
        ResponseEntity<ImportSessionResponse> created = restTemplate.exchange(
                "/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(null, null, vendorId, expectedId,
                        List.of(new DeliveryRequest.Line(productId, unit, quantity, null))), json(tenant)),
                ImportSessionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sessionId = created.getBody().id();

        ResponseEntity<String> commit = restTemplate.exchange("/api/imports/" + sessionId + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(commit.getStatusCode()).as(commit.getBody()).isEqualTo(HttpStatus.OK);
        return sessionId;
    }

    private ExpectedDeliveryResponse create(TenantLoginResponse tenant, ExpectedDeliveryRequest request) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ExpectedDeliveryResponse expectation(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/expected-deliveries/" + id, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
    }

    private List<String> references(TenantLoginResponse tenant, String query) {
        Map<String, Object> page = restTemplate.exchange("/api/expected-deliveries" + query, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        return content.stream().map(row -> (String) row.get("reference")).toList();
    }

    private ProductResponse productById(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/products/" + id, HttpMethod.GET, new HttpEntity<>(json(tenant)),
                ProductResponse.class).getBody();
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null, null,
                        null, null, null), json(tenant)),
                CompanyVendorResponse.class).getBody();
    }

    private ProductResponse product(
            TenantLoginResponse tenant, String name, String sku, String unit, String pack, BigDecimal size) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, unit, pack, size, null), partHeaders));
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
