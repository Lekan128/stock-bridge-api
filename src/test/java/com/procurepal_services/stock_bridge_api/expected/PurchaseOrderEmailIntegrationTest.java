package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.email.EmailSender;
import com.procurepal_services.stock_bridge_api.email.webhook.EmailSuppressionService;
import com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmailStatus;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * PURCHASE_ORDER_PLAN.md phase 3: emailing a purchase order to its supplier, and being told the
 * truth about what happened. The provider is a mock; everything else is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PurchaseOrderEmailIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurchaseOrderEmailOutcomes outcomes;

    @Autowired
    private EmailSuppressionService suppressionService;

    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void providerAcceptsEverythingByDefault() {
        reset(emailSender);
        when(emailSender.sendNow(any())).thenReturn(new EmailSender.SendResult(true, "msg-" + UUID.randomUUID(), null));
    }

    @Test
    void theSwitchOnEmailsTheOrderWithItsPdfAndTheOrderSaysSo() {
        TenantLoginResponse tenant = verifiedSignup("Emailing Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-RICE");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);

        assertThat(created.email()).isNotNull();
        assertThat(created.email().status()).isEqualTo(PurchaseOrderEmailStatus.SENT);
        assertThat(created.email().toAddress()).isEqualTo(tony.email().toLowerCase());
        assertThat(created.email().problem()).isNull();

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).sendNow(sent.capture());
        EmailMessage message = sent.getValue();
        assertThat(message.kind()).isEqualTo(EmailKind.SUPPLIER_DOCUMENT);
        assertThat(message.to()).containsExactly(tony.email().toLowerCase());
        assertThat(message.subject()).startsWith("Purchase order PO-0001 from Emailing Co");
        assertThat(message.attachments()).hasSize(1);
        assertThat(message.attachments().get(0).filename()).isEqualTo("PO-0001 Tony Stores.pdf");
        assertThat(message.attachments().get(0).content()).startsWith("%PDF".getBytes());
        assertThat(message.replyTo()).isNotBlank();
        assertThat(message.fromName()).contains("Emailing Co");
        assertThat(message.htmlBody()).contains("Rice").contains("Please reply with your price");
        assertThat(message.htmlBody()).doesNotContain("₦");
    }

    @Test
    void aSupplierWhoWasEmailedSuccessfullyStartsOnNextTimeOthersStayOff() {
        TenantLoginResponse tenant = verifiedSignup("Remember Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        CompanyVendorResponse ade = vendor(tenant, "Ade Foods", uniqueEmail("ade"));
        ProductResponse rice = product(tenant, "Rice", "POE-REM");
        assertThat(tony.emailOrders()).isFalse();

        order(tenant, tony.id(), rice.id(), true);

        assertThat(vendorById(tenant, tony.id()).emailOrders()).isTrue();
        assertThat(vendorById(tenant, ade.id()).emailOrders()).isFalse();
    }

    @Test
    void withTheSwitchOffNothingIsSentAndItCanBeEmailedLater() {
        TenantLoginResponse tenant = verifiedSignup("Later Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-LATER");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), false);
        assertThat(created.email()).isNull();
        verify(emailSender, never()).sendNow(any());

        ExpectedDeliveryResponse later = post(tenant, "/api/expected-deliveries/" + created.id() + "/email");

        assertThat(later.email().status()).isEqualTo(PurchaseOrderEmailStatus.SENT);
        verify(emailSender).sendNow(any());
    }

    @Test
    void anUnconfirmedBuyerGetsAPlainSentenceNotAnError() {
        TenantLoginResponse tenant = signup("Unconfirmed Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-UNV");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);

        assertThat(created.poNumber()).isEqualTo("PO-0001");
        assertThat(created.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(created.email().problem()).isEqualTo("Confirm your email address first, then send this.");
        verify(emailSender, never()).sendNow(any());
    }

    @Test
    void aSupplierWithNoEmailSaysSoByName() {
        TenantLoginResponse tenant = verifiedSignup("No Email Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", null);
        ProductResponse rice = product(tenant, "Rice", "POE-NOE");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);

        assertThat(created.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(created.email().problem()).isEqualTo("Tony Stores has no email saved.");
        verify(emailSender, never()).sendNow(any());
    }

    @Test
    void anAddressThatHasBouncedBeforeIsNotMailedAgain() {
        TenantLoginResponse tenant = verifiedSignup("Suppressed Co");
        String address = uniqueEmail("dead");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", address);
        ProductResponse rice = product(tenant, "Rice", "POE-SUP");
        suppressionService.suppressPermanentBounce(address, "test-bounce", "mailbox does not exist");

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);

        assertThat(created.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(created.email().problem()).contains("can't receive email").contains("bounced before");
        verify(emailSender, never()).sendNow(any());
    }

    @Test
    void whenTheProviderRefusesTheOrderStillExistsAndTheSwitchStaysOff() {
        TenantLoginResponse tenant = verifiedSignup("Refused Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-REF");
        when(emailSender.sendNow(any()))
                .thenReturn(new EmailSender.SendResult(false, null, "We couldn't send it. Try again in a moment."));

        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);

        assertThat(created.poNumber()).isEqualTo("PO-0001");
        assertThat(created.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(created.email().problem()).isEqualTo("We couldn't send it. Try again in a moment.");
        assertThat(vendorById(tenant, tony.id()).emailOrders()).isFalse();
    }

    @Test
    void aBounceReportedLaterChangesTheOrderAndSwitchesTheSupplierOff() {
        TenantLoginResponse tenant = verifiedSignup("Bounce Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-BNC");
        when(emailSender.sendNow(any())).thenReturn(new EmailSender.SendResult(true, "resend-id-42", null));
        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), true);
        assertThat(vendorById(tenant, tony.id()).emailOrders()).isTrue();

        outcomes.bounced("resend-id-42");

        ExpectedDeliveryResponse after = get(tenant, created.id());
        assertThat(after.email().status()).isEqualTo(PurchaseOrderEmailStatus.BOUNCED);
        assertThat(after.email().problem()).startsWith("It didn't reach ").contains(tony.email().toLowerCase());
        assertThat(vendorById(tenant, tony.id()).emailOrders()).isFalse();
    }

    @Test
    void aBounceForSomeoneElsesMailChangesNothing() {
        outcomes.bounced("a-message-id-that-is-not-ours");
        outcomes.bounced(null);
    }

    @Test
    void correctingTheSuppliersAddressTakesTheSwitchBackToOff() {
        TenantLoginResponse tenant = verifiedSignup("Fix Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-FIX");
        order(tenant, tony.id(), rice.id(), true);
        assertThat(vendorById(tenant, tony.id()).emailOrders()).isTrue();

        ResponseEntity<CompanyVendorResponse> updated = restTemplate.exchange(
                "/api/company-vendors/" + tony.id(), HttpMethod.PUT,
                new HttpEntity<>(new CompanyVendorRequest("Tony Stores", "08030000000", uniqueEmail("tony-fixed"),
                        null, null, null, null, null, null, null, null, null), json(tenant)),
                CompanyVendorResponse.class);

        assertThat(updated.getBody().emailOrders()).isFalse();
    }

    @Test
    void aCompanyCannotEmailMoreThanItsDailyLimit() {
        TenantLoginResponse tenant = verifiedSignup("Cap Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores", uniqueEmail("tony"));
        ProductResponse rice = product(tenant, "Rice", "POE-CAP");
        ExpectedDeliveryResponse created = order(tenant, tony.id(), rice.id(), false);
        UUID companyId = jdbc.queryForObject(
                "SELECT client_id FROM expected_deliveries WHERE id = ?", UUID.class, created.id());
        for (int i = 0; i < PurchaseOrderEmailService.DAILY_COMPANY_CAP; i++) {
            jdbc.update("INSERT INTO purchase_order_emails (client_id, expected_delivery_id, to_address, status) "
                    + "VALUES (?, ?, ?, 'SENT')", companyId, created.id(), "someone" + i + "@suppliers.test");
        }

        ExpectedDeliveryResponse blocked = post(tenant, "/api/expected-deliveries/" + created.id() + "/email");

        assertThat(blocked.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(blocked.email().problem()).contains("today's limit of 50");
        verify(emailSender, never()).sendNow(any());
    }

    @Test
    void oneAddressCannotBeMailedWithoutEndAcrossCompanies() {
        TenantLoginResponse a = verifiedSignup("Spam A");
        TenantLoginResponse b = verifiedSignup("Spam B");
        String victim = uniqueEmail("victim");
        for (int i = 0; i < PurchaseOrderEmailService.DAILY_ADDRESS_CAP; i++) {
            order(a, vendor(a, "V" + i, victim).id(), product(a, "Rice " + i, "SPA-" + i).id(), true);
        }
        ExpectedDeliveryResponse fromB = order(b, vendor(b, "Tony", victim).id(), product(b, "Rice", "SPB").id(), true);

        assertThat(fromB.email().status()).isEqualTo(PurchaseOrderEmailStatus.FAILED);
        assertThat(fromB.email().problem()).contains("Too many orders");
    }

    @Test
    void anOrderThatWasOnlyWrittenDownCannotBeEmailed() {
        TenantLoginResponse tenant = verifiedSignup("Old Way Co");
        ProductResponse rice = product(tenant, "Rice", "POE-OLD");
        ExpectedDeliveryResponse oldWay = create(tenant, new ExpectedDeliveryRequest(
                null, null, null, null,
                List.of(new ExpectedDeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null))));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/expected-deliveries/" + oldWay.id() + "/email", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(emailSender, never()).sendNow(any());
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private ExpectedDeliveryResponse order(
            TenantLoginResponse tenant, UUID vendorId, UUID productId, boolean emailIt) {
        return create(tenant, new ExpectedDeliveryRequest(
                vendorId, "2026-11-12", null, null,
                List.of(new ExpectedDeliveryRequest.Line(productId, "KG", new BigDecimal("10"), null)),
                null, true, emailIt));
    }

    private ExpectedDeliveryResponse create(TenantLoginResponse tenant, ExpectedDeliveryRequest request) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                "/api/expected-deliveries", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ExpectedDeliveryResponse post(TenantLoginResponse tenant, String path) {
        ResponseEntity<ExpectedDeliveryResponse> response = restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ExpectedDeliveryResponse get(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/expected-deliveries/" + id, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), ExpectedDeliveryResponse.class).getBody();
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name, String email) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "08030000000", email, null, null, null, null, null,
                        null, null, null, null), json(tenant)),
                CompanyVendorResponse.class).getBody();
    }

    private CompanyVendorResponse vendorById(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/company-vendors/" + id, HttpMethod.GET,
                new HttpEntity<>(json(tenant)),
                com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorDetailResponse.class)
                .getBody().vendor();
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

    private static String uniqueEmail(String who) {
        // Not a reserved test domain, which EmailSender would refuse - though the sender is mocked here.
        return who + "-" + UUID.randomUUID().toString().substring(0, 8) + "@suppliers.test";
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup", new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    /** A signed-up owner whose email has been confirmed, which is what sending to a supplier needs. */
    private TenantLoginResponse verifiedSignup(String name) {
        TenantLoginResponse tenant = signup(name);
        jdbc.update("UPDATE users SET is_email_verified = TRUE, email_verified_at = now() WHERE id = ?",
                tenant.user().id());
        return tenant;
    }
}
