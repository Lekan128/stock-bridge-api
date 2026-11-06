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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * The purchase order redesign of 2026-11-04: the picker's stock figures, "ordered by phone", the
 * list's chips and search, and changing an order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PurchaseOrderRedesignIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void thePickerSaysWhatIsLeftWhatIsComingAndLastTimeInTheLinesOwnUnit() {
        TenantLoginResponse tenant = signup("Picker Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        // 100 kg on hand in 50 kg bags, with the alert at 150 kg: 2 bags left, and low.
        ProductResponse rice = product(tenant, "Rice", "RD-RICE", 150, tony.id(), 100);

        JsonNode low = get(tenant, "/api/expected-deliveries/picker?runningLow=true&vendorId=" + tony.id());
        JsonNode bag = lineOf(low, rice.id(), true);
        assertThat(bag).isNotNull();
        assertThat(bag.get("left").decimalValue()).isEqualByComparingTo("2");
        assertThat(bag.get("stockState").asText()).isEqualTo("LOW");
        assertThat(bag.get("usual").asBoolean()).isTrue();
        assertThat(bag.has("coming")).isFalse();
        assertThat(bag.has("lastOrdered")).isFalse();
        JsonNode loose = lineOf(low, rice.id(), false);
        assertThat(loose.get("left").decimalValue()).isEqualByComparingTo("100");
        assertThat(loose.get("usual").asBoolean()).isFalse();

        create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), bag.get("unit").asText(), new BigDecimal("3"), null)),
                null, true));

        JsonNode after = get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id());
        JsonNode bagAfter = lineOf(after, rice.id(), true);
        assertThat(bagAfter.get("coming").decimalValue()).isEqualByComparingTo("3");
        assertThat(bagAfter.get("lastOrdered").decimalValue()).isEqualByComparingTo("3");
        // 3 bags coming is 150 kg coming, said per kg on the loose line.
        assertThat(lineOf(after, rice.id(), false).get("coming").decimalValue()).isEqualByComparingTo("150");

        JsonNode recent = get(tenant, "/api/expected-deliveries/recent-suppliers");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).get("vendorId").asText()).isEqualTo(tony.id().toString());
        assertThat(recent.get(0).get("products").get(0).asText()).isEqualTo("Rice");
    }

    @Test
    void anOrderPlacedByPhoneIsNumberedButNotWaitingToBeSent() {
        TenantLoginResponse tenant = signup("Phone Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-PH", null, null, 0);

        ExpectedDeliveryResponse byPhone = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.TEN, null)),
                null, true, false, true));
        ExpectedDeliveryResponse toSend = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null)), null, true));

        assertThat(byPhone.poNumber()).isEqualTo("PO-0001");
        assertThat(byPhone.sentVia()).isEqualTo("PHONE");
        assertThat(byPhone.waitingToBeSent()).isFalse();
        assertThat(byPhone.createdByName()).isNotBlank();
        assertThat(toSend.waitingToBeSent()).isTrue();

        JsonNode counts = get(tenant, "/api/expected-deliveries/counts");
        assertThat(counts.get("toSend").asLong()).isEqualTo(1);
        assertThat(counts.get("waiting").asLong()).isEqualTo(1);
        assertThat(counts.get("all").asLong()).isEqualTo(2);

        JsonNode toSendPage = get(tenant, "/api/expected-deliveries?view=TO_SEND");
        assertThat(toSendPage.get("content")).hasSize(1);
        assertThat(toSendPage.get("content").get(0).get("poNumber").asText()).isEqualTo("PO-0002");
    }

    @Test
    void theListIsSearchedByNumberSupplierOrProduct() {
        TenantLoginResponse tenant = signup("Search Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        CompanyVendorResponse ade = vendor(tenant, "Ade Farms");
        ProductResponse rice = product(tenant, "Basmati Rice", "RD-S1", null, null, 0);
        ProductResponse oil = product(tenant, "Palm Oil", "RD-S2", null, null, 0);
        create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null)), null, true));
        create(tenant, new ExpectedDeliveryRequest(ade.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(oil.id(), "KG", BigDecimal.ONE, null)), null, true));

        assertThat(get(tenant, "/api/expected-deliveries?search=basmati").get("content")).hasSize(1);
        assertThat(get(tenant, "/api/expected-deliveries?search=ade").get("content").get(0).get("vendorName").asText())
                .isEqualTo("Ade Farms");
        assertThat(get(tenant, "/api/expected-deliveries?search=PO-0001").get("content")).hasSize(1);
        assertThat(get(tenant, "/api/expected-deliveries?search=100%25").get("content")).isEmpty();
        assertThat(get(tenant, "/api/expected-deliveries?view=OPEN&vendorId=" + tony.id()).get("content")).hasSize(1);
    }

    @Test
    void changingAnOrderTheSupplierHasMakesTheNextRevisionAndPutsItBackOnToSend() {
        TenantLoginResponse tenant = signup("Change Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-CH", null, null, 0);
        ProductResponse beans = product(tenant, "Beans", "RD-CH2", null, null, 0);
        ExpectedDeliveryResponse order = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.TEN, null)), null, true));

        // Not sent yet: a change is still the first order.
        ExpectedDeliveryResponse unsent = change(tenant, order.id(), List.of(
                new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("12"), null)));
        assertThat(unsent.revision()).isEqualTo(1);

        restTemplate.exchange("/api/expected-deliveries/" + order.id() + "/shared?channel=WHATSAPP",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        ExpectedDeliveryResponse sent = restTemplate.exchange("/api/expected-deliveries/" + order.id(), HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
        assertThat(sent.waitingToBeSent()).isFalse();
        assertThat(sent.sentVia()).isEqualTo("WHATSAPP");

        ExpectedDeliveryResponse revised = change(tenant, order.id(), List.of(
                new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("15"), null),
                new ExpectedDeliveryRequest.Line(beans.id(), "KG", new BigDecimal("2"), null)));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.waitingToBeSent()).isTrue();
        assertThat(revised.lines()).hasSize(2);

        // A second change before sending the first stays revision 2.
        ExpectedDeliveryResponse again = change(tenant, order.id(), List.of(
                new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("15"), null)));
        assertThat(again.revision()).isEqualTo(2);
        assertThat(again.lines()).hasSize(1);
    }

    @Test
    void whatWasAskedOfASupplierBeforeIsSuggestedAndAChangedOrderIsNotComingToItself() {
        TenantLoginResponse tenant = signup("Before Co");
        CompanyVendorResponse john = vendor(tenant, "John's Stores");
        // Linked to nobody: only ever asked of John on a purchase order.
        ProductResponse ginger = product(tenant, "Ginger", "RD-GIN", null, null, 0);
        ProductResponse called = product(tenant, "Pepper", "RD-PEP", null, null, 0);
        ExpectedDeliveryResponse first = create(tenant, new ExpectedDeliveryRequest(john.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(ginger.id(), "KG", new BigDecimal("4"), null)), null, true));
        ExpectedDeliveryResponse second = create(tenant, new ExpectedDeliveryRequest(john.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(ginger.id(), "KG", new BigDecimal("5"), null)), null, true));
        ExpectedDeliveryResponse off = create(tenant, new ExpectedDeliveryRequest(john.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(called.id(), "KG", BigDecimal.ONE, null)), null, true));
        restTemplate.exchange("/api/expected-deliveries/" + off.id() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), String.class);

        JsonNode before = get(tenant, "/api/expected-deliveries/picker?limit=6&vendorId=" + john.id());
        assertThat(lineOf(before, ginger.id(), false)).isNotNull();
        // A called-off order is not "from John before".
        assertThat(lineOf(before, called.id(), false)).isNull();
        assertThat(lineOf(before, ginger.id(), false).get("coming").decimalValue()).isEqualByComparingTo("9");

        // Changing the second order: only the first order's 4 kg are coming from elsewhere.
        JsonNode changing = get(tenant, "/api/expected-deliveries/picker?vendorId=" + john.id()
                + "&productIds=" + ginger.id() + "&excludeOrderId=" + second.id());
        assertThat(lineOf(changing, ginger.id(), false).get("coming").decimalValue()).isEqualByComparingTo("4");
        assertThat(first.poNumber()).isEqualTo("PO-0001");
    }

    @Test
    void goodsArrivingTakeAnOrderOffToSendAndItsHistorySaysWho() {
        TenantLoginResponse tenant = signup("Arrived Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-ARR", null, null, 0);
        ExpectedDeliveryResponse order = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.TEN, null)), null, true));
        assertThat(order.waitingToBeSent()).isTrue();

        // Tony turned up before anybody marked it sent; 4 of the 10 came.
        ResponseEntity<ImportSessionResponse> created = restTemplate.exchange("/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(null, null, tony.id(), order.id(),
                        List.of(new DeliveryRequest.Line(rice.id(), "KG", new BigDecimal("4"), null))), json(tenant)),
                ImportSessionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> commit = restTemplate.exchange("/api/imports/" + created.getBody().id() + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(commit.getStatusCode()).as(commit.getBody()).isEqualTo(HttpStatus.OK);

        ExpectedDeliveryResponse after = restTemplate.exchange("/api/expected-deliveries/" + order.id(), HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
        assertThat(after.waitingToBeSent()).isFalse();
        assertThat(after.receivable()).isTrue();
        assertThat(after.receipts()).hasSize(1);
        assertThat(get(tenant, "/api/expected-deliveries/counts").get("toSend").asLong()).isZero();
    }

    @Test
    void aPricedOrderTotalsItsLinesAndDeliveryEverywhereTheSupplierLooks() {
        TenantLoginResponse tenant = signup("Priced Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-PRICE", null, tony.id(), 100);
        String bags = lineOf(get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id()), rice.id(), true)
                .get("unit").asText();

        ExpectedDeliveryResponse order = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), bags, new BigDecimal("3"), new BigDecimal("45000"))),
                null, true, false, false, new BigDecimal("5000")));

        assertThat(order.total()).isEqualByComparingTo("140000");
        assertThat(order.deliveryCharge()).isEqualByComparingTo("5000");
        ExpectedDeliveryResponse full = restTemplate.exchange("/api/expected-deliveries/" + order.id(), HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
        assertThat(full.share().message())
                .contains("Rice: 3 × Bag · 50 kg at ₦45,000 = ₦135,000")
                .contains("Delivery: ₦5,000")
                .contains("Total: ₦140,000")
                .contains("Please confirm and tell us when to expect delivery.")
                .doesNotContain("reply with your price");

        JsonNode page = restTemplate.getForObject(
                "/api/public/purchase-orders/" + full.share().token(), JsonNode.class);
        assertThat(page.get("total").decimalValue()).isEqualByComparingTo("140000");
        assertThat(page.get("deliveryCharge").decimalValue()).isEqualByComparingTo("5000");
        assertThat(page.get("lines").get(0).get("price").decimalValue()).isEqualByComparingTo("45000");
        assertThat(page.get("lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("135000");

        // Changing the price is a change like any other.
        ExpectedDeliveryResponse changed = restTemplate.exchange("/api/expected-deliveries/" + order.id(), HttpMethod.PUT,
                new HttpEntity<>(new ExpectedDeliveryRequest(null, null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), bags, new BigDecimal("3"), new BigDecimal("44000"))),
                        null, null, null, null, BigDecimal.ZERO), json(tenant)),
                ExpectedDeliveryResponse.class).getBody();
        assertThat(changed.total()).as("free delivery is zero, not missing").isEqualByComparingTo("132000");
        assertThat(changed.deliveryCharge()).isEqualByComparingTo("0");
    }

    @Test
    void aNegativePriceOrDeliveryChargeIsRefused() {
        TenantLoginResponse tenant = signup("Negative Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-NEG", null, null, 0);

        ResponseEntity<String> price = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(tony.id(), null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, new BigDecimal("-1"))),
                        null, true), json(tenant)), String.class);
        assertThat(price.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> delivery = restTemplate.exchange("/api/expected-deliveries", HttpMethod.POST,
                new HttpEntity<>(new ExpectedDeliveryRequest(tony.id(), null, null, null,
                        List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, BigDecimal.TEN)),
                        null, true, false, false, new BigDecimal("-5")), json(tenant)), String.class);
        assertThat(delivery.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aLinesPriceStartsAtWhatWasLastPaidToThatSupplierOrLastAgreedWithThem() {
        TenantLoginResponse tenant = signup("Last Price Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-LAST", null, tony.id(), 100);
        String bags = lineOf(get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id()), rice.id(), true)
                .get("unit").asText();
        assertThat(lineOf(get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id()), rice.id(), true)
                .has("lastPrice")).as("never priced from Tony").isFalse();

        // Tony brought 2 bags at ₦44,500 a bag.
        ResponseEntity<ImportSessionResponse> created = restTemplate.exchange("/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(null, null, tony.id(), null,
                        List.of(new DeliveryRequest.Line(rice.id(), bags, new BigDecimal("2"), new BigDecimal("44500")))),
                        json(tenant)),
                ImportSessionResponse.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> commit = restTemplate.exchange("/api/imports/" + created.getBody().id() + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(commit.getStatusCode()).as(commit.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode paid = get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id());
        JsonNode bag = lineOf(paid, rice.id(), true);
        assertThat(bag.get("lastPrice").decimalValue()).isEqualByComparingTo("44500");
        assertThat(bag.get("lastPriceKind").asText()).isEqualTo("PAID");
        assertThat(bag.has("lastPriceOn")).isTrue();
        // Per kg it is a fiftieth of that: the price follows the line's own unit.
        assertThat(lineOf(paid, rice.id(), false).get("lastPrice").decimalValue()).isEqualByComparingTo("890");
        // Another supplier has no price from Tony's delivery.
        CompanyVendorResponse ade = vendor(tenant, "Ade Foods");
        JsonNode fromAde = lineOf(get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + ade.id()), rice.id(), true);
        assertThat(fromAde == null || !fromAde.has("lastPrice")).isTrue();

        // A newer order agreed ₦46,000: that is now the price to start from.
        create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), bags, BigDecimal.ONE, new BigDecimal("46000"))),
                null, true));
        JsonNode agreed = lineOf(get(tenant, "/api/expected-deliveries/picker?search=rice&vendorId=" + tony.id()), rice.id(), true);
        assertThat(agreed.get("lastPrice").decimalValue()).isEqualByComparingTo("46000");
        assertThat(agreed.get("lastPriceKind").asText()).isEqualTo("ORDERED");
    }

    @Test
    void goodsSentBackAtTheGateNeverBecomeStockAndAreStillOwedOnTheOrder() {
        TenantLoginResponse tenant = signup("Send Back Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice", "RD-BACK", null, null, 0);
        ExpectedDeliveryResponse order = create(tenant, new ExpectedDeliveryRequest(tony.id(), null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", new BigDecimal("20"), new BigDecimal("900"))),
                null, true));

        // No reason: refused. Everything sent back: refused (nothing would go in).
        assertThat(delivery(tenant, tony.id(), order.id(), new DeliveryRequest.Line(rice.id(), "KG", new BigDecimal("17"),
                null, new BigDecimal("3"), null, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delivery(tenant, tony.id(), order.id(), new DeliveryRequest.Line(rice.id(), "KG", BigDecimal.ZERO,
                null, new BigDecimal("20"), "DAMAGED", null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delivery(tenant, tony.id(), order.id(), new DeliveryRequest.Line(rice.id(), "KG", new BigDecimal("17"),
                null, new BigDecimal("3"), "OTHER", " ")).getStatusCode()).as("Other needs a note").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> created = delivery(tenant, tony.id(), order.id(), new DeliveryRequest.Line(rice.id(), "KG",
                new BigDecimal("17"), new BigDecimal("900"), new BigDecimal("3"), "DAMAGED", null));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode session = tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.getBody());
        ResponseEntity<String> commit = restTemplate.exchange("/api/imports/" + session.get("id").asText() + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(commit.getStatusCode()).as(commit.getBody()).isEqualTo(HttpStatus.OK);

        ExpectedDeliveryResponse after = restTemplate.exchange("/api/expected-deliveries/" + order.id(), HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
        assertThat(after.lines().get(0).receivedQuantity()).isEqualByComparingTo("17");
        assertThat(after.lines().get(0).outstanding()).as("the 3 sent back are still owed").isEqualByComparingTo("3");
        assertThat(after.receipts()).hasSize(1);
        assertThat(after.receipts().get(0).sentBack()).hasSize(1);
        assertThat(after.receipts().get(0).sentBack().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(after.receipts().get(0).sentBack().get(0).reason()).isEqualTo("DAMAGED");
        JsonNode product = get(tenant, "/api/products/" + rice.id());
        assertThat(product.get("quantityOnHand").decimalValue()).as("only what was taken is stock").isEqualByComparingTo("17");
    }

    private ResponseEntity<String> delivery(TenantLoginResponse tenant, UUID vendorId, UUID orderId, DeliveryRequest.Line line) {
        return restTemplate.exchange("/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(null, null, vendorId, orderId, List.of(line)), json(tenant)),
                String.class);
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private static JsonNode lineOf(JsonNode lines, UUID productId, boolean pack) {
        for (JsonNode line : lines) {
            if (line.get("productId").asText().equals(productId.toString()) && line.get("pack").asBoolean() == pack) {
                return line;
            }
        }
        return null;
    }

    private ExpectedDeliveryResponse change(
            TenantLoginResponse tenant, UUID id, List<ExpectedDeliveryRequest.Line> lines) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries/" + id, HttpMethod.PUT,
                new HttpEntity<>(new ExpectedDeliveryRequest(null, null, null, null, lines), json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode get(TenantLoginResponse tenant, String path) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(json(tenant)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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

    /** A kg product; with a supplier, bought from them in 50 kg bags with {@code openingKg} on hand. */
    private ProductResponse product(
            TenantLoginResponse tenant, String name, String sku, Integer lowAtKg, UUID vendorId, int openingKg) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        CreateProductRequest.InitialVendor initial = vendorId == null
                ? null
                : new CreateProductRequest.InitialVendor(vendorId, null, BigDecimal.ZERO, openingKg, "BAG", new BigDecimal("50"));
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, lowAtKg, "KG", null, null, initial), partHeaders));
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
