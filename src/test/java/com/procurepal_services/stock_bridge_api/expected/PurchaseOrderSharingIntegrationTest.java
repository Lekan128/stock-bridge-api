package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.expected.dto.PublicPurchaseOrderResponse;
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

/** PURCHASE_ORDER_PLAN.md phase 5: the link, the message, WhatsApp, and the page a supplier opens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PurchaseOrderSharingIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aPurchaseOrderCarriesALinkAMessageAndAWhatsAppAddress() {
        TenantLoginResponse tenant = signup("Share Co");
        ExpectedDeliveryResponse order = order(tenant, vendor(tenant, "Tony Stores", "0803 123 4567"),
                product(tenant, "Rice (Mama Gold)", "SH-RICE"));

        ExpectedDeliveryResponse.Share share = order.share();
        assertThat(share).isNotNull();
        assertThat(share.token()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(share.url()).isEqualTo("http://localhost:5173/o/" + share.token());
        assertThat(share.message())
                .startsWith("Hello Tony Stores, this is purchase order PO-0001 from Share Co")
                .contains("• Rice (Mama Gold): 10 × Loose · kg")
                .contains("Needed by 12 Nov.")
                .contains("Please reply with your price.")
                .endsWith("Full order: " + share.url())
                .doesNotContain("₦");
        assertThat(share.whatsappUrl()).startsWith("https://wa.me/2348031234567?text=Hello%20Tony%20Stores");
        assertThat(share.whatsappOpenedAt()).isNull();
        assertThat(share.messageCopiedAt()).isNull();
    }

    @Test
    void nothingToShareOnAnOrderThatWasOnlyWrittenDown() {
        TenantLoginResponse tenant = signup("Old Way Co");
        ProductResponse rice = product(tenant, "Rice", "SH-OLD");

        ExpectedDeliveryResponse oldWay = create(tenant, new ExpectedDeliveryRequest(
                null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null))));

        assertThat(oldWay.share()).isNull();
    }

    @Test
    void openingWhatsAppAndCopyingAreWrittenOnTheOrder() {
        TenantLoginResponse tenant = signup("Events Co");
        ExpectedDeliveryResponse order = order(tenant, vendor(tenant, "Tony Stores", "08031234567"),
                product(tenant, "Rice", "SH-EV"));

        ExpectedDeliveryResponse afterWhatsApp = post(tenant, order.id(), "WHATSAPP");
        assertThat(afterWhatsApp.share().whatsappOpenedAt()).isNotNull();
        assertThat(afterWhatsApp.share().messageCopiedAt()).isNull();

        ExpectedDeliveryResponse afterCopy = post(tenant, order.id(), "MESSAGE");
        assertThat(afterCopy.share().messageCopiedAt()).isNotNull();
        assertThat(afterCopy.share().whatsappOpenedAt()).isNotNull();
    }

    @Test
    void aSupplierOpensTheOrderWithoutLoggingInAndSeesNoPrices() {
        TenantLoginResponse tenant = signup("Public Co");
        ExpectedDeliveryResponse order = order(tenant, vendor(tenant, "Tony Stores", "08031234567"),
                product(tenant, "Rice", "SH-PUB"));
        String token = order.share().token();

        ResponseEntity<PublicPurchaseOrderResponse> page = restTemplate.getForEntity(
                "/api/public/purchase-orders/" + token, PublicPurchaseOrderResponse.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublicPurchaseOrderResponse body = page.getBody();
        assertThat(body.poNumber()).isEqualTo("PO-0001");
        assertThat(body.calledOff()).isFalse();
        assertThat(body.buyer().name()).startsWith("Public Co");
        assertThat(body.lines()).hasSize(1);
        assertThat(body.lines().get(0).productName()).isEqualTo("Rice");
        assertThat(body.pdfPath()).isEqualTo("/api/public/purchase-orders/" + token + "/pdf");
        String raw = restTemplate.getForObject("/api/public/purchase-orders/" + token, String.class);
        assertThat(raw).doesNotContain("price").doesNotContain("email").doesNotContain("total");
    }

    @Test
    void theSuppliersPdfOpensInlineAndIsNeverKeptByAnythingInBetween() {
        TenantLoginResponse tenant = signup("Pdf Link Co");
        ExpectedDeliveryResponse order = order(tenant, vendor(tenant, "Tony Stores", "08031234567"),
                product(tenant, "Rice", "SH-PDF"));

        ResponseEntity<byte[]> pdf = restTemplate.getForEntity(
                "/api/public/purchase-orders/" + order.share().token() + "/pdf", byte[].class);

        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(pdf.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        assertThat(pdf.getHeaders().getContentDisposition().getFilename()).isEqualTo("PO-0001 Tony Stores.pdf");
        assertThat(pdf.getHeaders().getCacheControl()).contains("no-store");
        assertThat(pdf.getBody()).startsWith("%PDF".getBytes());
    }

    @Test
    void anUnknownOrMalformedLinkIsJustNotFound() {
        for (String token : List.of("x".repeat(32), "short", "../../etc/passwd" + "y".repeat(20))) {
            ResponseEntity<String> response = restTemplate.getForEntity("/api/public/purchase-orders/" + token, String.class);
            assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void aCalledOffOrderSaysSoAndItsPdfIsGone() {
        TenantLoginResponse tenant = signup("Cancel Co");
        ExpectedDeliveryResponse order = order(tenant, vendor(tenant, "Tony Stores", "08031234567"),
                product(tenant, "Rice", "SH-CAN"));
        restTemplate.exchange("/api/expected-deliveries/" + order.id() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class);

        PublicPurchaseOrderResponse page = restTemplate.getForObject(
                "/api/public/purchase-orders/" + order.share().token(), PublicPurchaseOrderResponse.class);
        ResponseEntity<String> pdf = restTemplate.getForEntity(
                "/api/public/purchase-orders/" + order.share().token() + "/pdf", String.class);

        assertThat(page.calledOff()).isTrue();
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private ExpectedDeliveryResponse order(TenantLoginResponse tenant, CompanyVendorResponse vendor, ProductResponse product) {
        return create(tenant, new ExpectedDeliveryRequest(
                vendor.id(), "2099-11-12", null, null,
                List.of(new ExpectedDeliveryRequest.Line(product.id(), "KG", new BigDecimal("10"), null)),
                null, true));
    }

    private ExpectedDeliveryResponse create(TenantLoginResponse tenant, ExpectedDeliveryRequest request) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ExpectedDeliveryResponse post(TenantLoginResponse tenant, UUID id, String channel) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries/" + id + "/shared?channel=" + channel, HttpMethod.POST,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name, String phone) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, phone, null, null, null, null, null, null,
                        null, null, null, null), json(tenant)),
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
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers),
                ProductResponse.class).getBody();
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup", new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }
}
