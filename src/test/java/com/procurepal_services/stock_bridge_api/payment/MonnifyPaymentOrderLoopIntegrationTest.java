package com.procurepal_services.stock_bridge_api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentRequest;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentResponse;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.PaymentRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The two halves of the loop, joined: a real Monnify payment applied through M4's
 * real {@code OrderPaymentApplicationService}, ending in real incoming stock on the
 * buyer's own product row.
 *
 * <h2>Why this is a second test class</h2>
 * {@link MonnifyPaymentIntegrationTest} deliberately substitutes an UNGUARDED
 * double for the order module, so its replay assertions measure the payment
 * module's own idempotency guard in isolation. That isolation is exactly what
 * makes it unable to answer "do the two modules actually fit together" - the
 * question this class exists for. Here nothing is faked except the HTTP client.
 *
 * <p>Note the belt and braces this pins down: the payment module refuses to
 * re-apply a final attempt, AND the order module refuses to re-place a paid order.
 * Either alone would be enough; having both means a regression in one is not a
 * customer-visible incident.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration
 * test here. Requires {@code docker compose up -d} at the project root.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.monnify.api-key=MK_TEST_GC3B8XG2XX",
            "app.monnify.secret-key=" + MonnifyPaymentOrderLoopIntegrationTest.SECRET_KEY,
            "app.monnify.contract-code=5867418298",
            "app.monnify.base-url=https://sandbox.monnify.com",
            "app.monnify.require-webhook-signature=true",
            "app.monnify.reconciliation.enabled=false"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MonnifyPaymentOrderLoopIntegrationTest {

    static final String SECRET_KEY = "A663NRZA544DDPEM7KDN7Z8HRV6YXD8S";

    private static final BigDecimal UNIT_PRICE = new BigDecimal("14500.00");
    private static final int QUANTITY = 3;
    private static final BigDecimal ORDER_TOTAL = UNIT_PRICE.multiply(BigDecimal.valueOf(QUANTITY));

    /** Only the HTTP client is faked. Everything downstream is the real thing. */
    @org.springframework.test.context.bean.override.convention.TestBean(name = "monnifyRestClient")
    private MonnifyClient monnifyClient;

    static MonnifyClient monnifyClient() {
        return new FakeMonnifyClient();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private MonnifySignatureVerifier signatureVerifier;

    private FakeMonnifyClient monnify;
    private TenantLoginResponse buyer;
    private UUID buyerClientId;
    private UUID operatorClientId;

    @BeforeEach
    void setUp() {
        monnify = (FakeMonnifyClient) monnifyClient;
        monnify.reset();
        buyer = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("demo", "admin", "Demo1234!"), TenantLoginResponse.class);
        buyerClientId = clientRepository.findBySlug("demo").orElseThrow().getId();
        operatorClientId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();
    }

    /**
     * Browse -> order -> pay -> incoming stock, with the payment verified server-side
     * and applied exactly once even though the same success is reported three times.
     */
    @Test
    void averifiedPaymentPlacesTheOrderAndLandsIncomingStockInTheBuyersInventoryExactlyOnce() {
        UUID catalogProductId = newCatalogProduct();
        UUID orderId = newOrderFor(catalogProductId);

        // No buyer-side product row exists yet - this is a first-time purchase.
        assertThat(buyerProductFor(catalogProductId)).isNull();

        InitializePaymentResponse checkout = initialize(orderId);
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        String body = webhookBody(checkout);
        assertThat(postWebhook(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Monnify retries; the buyer also refreshes the return page.
        assertThat(postWebhook(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(checkout.paymentReference());

        // The money side.
        assertThat(paymentRepository.findByPaymentReference(checkout.paymentReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentProviderStatus.PAID);

        // The fulfilment side, done by M4 off the back of it.
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        // The point of the whole feature: it is in the buyer's inventory, as
        // INCOMING rather than usable, exactly once despite three reports.
        Product buyerProduct = buyerProductFor(catalogProductId);
        assertThat(buyerProduct).isNotNull();
        assertThat(buyerProduct.getClientId()).isEqualTo(buyerClientId);
        assertThat(buyerProduct.getIncomingQuantity()).isEqualTo(QUANTITY);
        assertThat(buyerProduct.getQuantityOnHand()).isZero();
        assertThat(buyerProduct.isMarketplaceListed()).isFalse();
    }

    /** An unpaid order must leave the buyer's inventory completely untouched. */
    @Test
    void anUnpaidOrderPutsNothingIntoTheBuyersInventory() {
        UUID catalogProductId = newCatalogProduct();
        UUID orderId = newOrderFor(catalogProductId);

        InitializePaymentResponse checkout = initialize(orderId);
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.FAILED, BigDecimal.ZERO);
        verify(checkout.paymentReference());

        assertThat(buyerProductFor(catalogProductId)).isNull();
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // ------------------------------------------------------------------------

    /**
     * A ProcurePal catalog product for the order line to point at.
     *
     * <p>Deliberately NOT {@code marketplaceListed}. It does not need to be -
     * incoming stock resolves the catalog product through
     * {@code BuyerCatalogLookup.findAnyCatalogProduct}, which has no listed/active
     * predicate precisely so an order already placed still renders after ProcurePal
     * unlists something. Listing it would put a fixture into the PUBLIC storefront
     * catalog, where M3's anonymous-browse test asserts every product has a slug and
     * a unit of measure - a shared-database fixture has no business appearing in
     * another module's assertions. Do not "fix" this by adding the flag.
     */
    private UUID newCatalogProduct() {
        String unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.set(operatorClientId);
        try {
            return productRepository.saveAndFlush(Product.builder()
                            .name("Loop test rice " + unique)
                            .sku("PP-LOOP-" + unique)
                            .unitPrice(UNIT_PRICE)
                            .quantityOnHand(500)
                            .active(true)
                            .unitOfMeasure("bag (50kg)")
                            .build())
                    .getId();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * An order awaiting Monnify payment, with one line against the catalog product.
     * Written through the repositories rather than through M4's checkout endpoint on
     * purpose: this test is about what a PAYMENT causes, and going through the cart
     * would couple it to a module still in flight.
     */
    private UUID newOrderFor(UUID catalogProductId) {
        String unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Product catalogProduct = productRepository.findById(catalogProductId).orElseThrow();

        TenantContext.set(buyerClientId);
        try {
            Order order = orderRepository.saveAndFlush(Order.builder()
                    .orderNumber("PP-LOOP-" + unique)
                    // NOT NULL since V11. Taken from the catalog product's owner,
                    // which is who is actually selling it.
                    .sellerClientId(catalogProduct.getClientId())
                    // NOT NULL since V12: every order names the checkout it came
                    // out of. A fixture order is its own checkout - a group of one -
                    // which is what V12's backfill made every pre-split row and what a
                    // single-seller basket still produces today.
                    .checkoutGroupId(UUID.randomUUID())
                    .status(OrderStatus.PENDING_PAYMENT)
                    .paymentStatus(PaymentStatus.PENDING)
                    .paymentMethod(PaymentMethod.MONNIFY)
                    .currency("NGN")
                    .subtotal(ORDER_TOTAL)
                    .deliveryFee(BigDecimal.ZERO)
                    .total(ORDER_TOTAL)
                    .deliveryContactName("Demo Buyer")
                    .deliveryContactPhone("+2348000000000")
                    .build());

            orderItemRepository.saveAndFlush(OrderItem.builder()
                    .order(order)
                    .productId(catalogProductId)
                    .productName(catalogProduct.getName())
                    .productSku(catalogProduct.getSku())
                    .unitOfMeasure(catalogProduct.getUnitOfMeasure())
                    .unitPrice(UNIT_PRICE)
                    .quantity(QUANTITY)
                    .lineTotal(ORDER_TOTAL)
                    .build());

            return order.getId();
        } finally {
            TenantContext.clear();
        }
    }

    /** The buyer's own row for a catalog product, matched the way reorder does - on sourceProductId. */
    private Product buyerProductFor(UUID catalogProductId) {
        return productRepository.findAll().stream()
                .filter(product -> buyerClientId.equals(product.getClientId()))
                .filter(product -> catalogProductId.equals(product.getSourceProductId()))
                .findFirst()
                .orElse(null);
    }

    private InitializePaymentResponse initialize(UUID orderId) {
        ResponseEntity<InitializePaymentResponse> response = restTemplate.exchange(
                "/api/payments/monnify/initialize",
                HttpMethod.POST,
                new HttpEntity<>(new InitializePaymentRequest(orderId), authHeaders()),
                InitializePaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void verify(String paymentReference) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/payments/" + paymentReference + "/verify",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Void> postWebhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("monnify-signature", signatureVerifier.computeSignature(body));
        return restTemplate.exchange(
                "/api/payments/monnify/webhook", HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    private String webhookBody(InitializePaymentResponse checkout) {
        return """
                {"eventType":"SUCCESSFUL_TRANSACTION","eventData":{"transactionReference":"%s",\
                "paymentReference":"%s","paidOn":"26/02/2020 09:38:13 AM","amountPaid":%s,\
                "totalPayable":%s,"paymentStatus":"PAID","paymentMethod":"CARD","currency":"NGN"}}"""
                .formatted(
                        checkout.transactionReference(), checkout.paymentReference(), ORDER_TOTAL, ORDER_TOTAL);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buyer.tokens().accessToken());
        return headers;
    }
}
