package com.procurepal_services.stock_bridge_api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Payment;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.entity.PaymentWebhookEvent;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentRequest;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentResponse;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.payment.dto.PaymentVerificationResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.PaymentRepository;
import com.procurepal_services.stock_bridge_api.repository.PaymentWebhookEventRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The payment loop end to end, over real HTTP against the local docker-compose
 * Postgres - see AuthIntegrationTest for why local Postgres rather than
 * Testcontainers. Requires {@code docker compose up -d} at the project root.
 *
 * <h2>No network</h2>
 * {@link FakeMonnifyClient} replaces the HTTP client entirely, so nothing here
 * reaches Monnify. What the fake replaces - endpoint paths, the response
 * envelope, token caching, URL encoding - is covered by
 * {@link MonnifyRestClientTest} against a stubbed request factory, so faking here
 * hides nothing.
 *
 * <h2>Why the order module is faked, not stubbed away</h2>
 * {@link RecordingOrderPaymentApplication} is a miniature of what the order module
 * does: it advances the order and bumps the buyer's {@code incoming_quantity}. The
 * idempotency assertions are therefore about real committed database state, not
 * about a call counter - "applyPaymentSuccess was invoked once" is a much weaker
 * claim than "the incoming stock went up by exactly one lot".
 *
 * <h2>Test properties</h2>
 * The reconciliation scheduler is off so the sweep can be driven deterministically
 * rather than raced, and the pending grace is zero so a freshly-created attempt is
 * eligible. Every assertion is scoped to its own paymentReference precisely
 * because that makes other tests' rows visible to the sweep.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.monnify.api-key=MK_TEST_GC3B8XG2XX",
            "app.monnify.secret-key=" + MonnifyPaymentIntegrationTest.SECRET_KEY,
            "app.monnify.contract-code=5867418298",
            "app.monnify.base-url=https://sandbox.monnify.com",
            "app.monnify.require-webhook-signature=true",
            "app.monnify.reconciliation.enabled=false",
            "app.monnify.reconciliation.pending-payment-grace=PT0S"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
// @TestComponent is excluded from component scanning, so the stand-in for the
// order module has to be imported explicitly.
@Import(RecordingOrderPaymentApplication.class)
class MonnifyPaymentIntegrationTest {

    static final String SECRET_KEY = "A663NRZA544DDPEM7KDN7Z8HRV6YXD8S";

    private static final String SIGNATURE_HEADER = "monnify-signature";
    private static final String INITIALIZE_PATH = "/api/payments/monnify/initialize";
    private static final String WEBHOOK_PATH = "/api/payments/monnify/webhook";
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("92000.00");
    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * Replaces the {@code MonnifyRestClient} bean for this context. @TestBean
     * substitutes by type, so the real client is never constructed and no socket
     * can be opened even by accident.
     */
    @TestBean(name = "monnifyRestClient")
    private MonnifyClient monnifyClient;

    static MonnifyClient monnifyClient() {
        return new FakeMonnifyClient();
    }

    /**
     * The order module has no implementation yet (it is built in parallel), so this
     * is the only OrderPaymentApplication bean in the context rather than an
     * override of one.
     */
    @Autowired
    private RecordingOrderPaymentApplication orderPaymentApplication;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private MonnifySignatureVerifier signatureVerifier;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    private FakeMonnifyClient monnify;
    private TenantLoginResponse buyer;
    private UUID buyerClientId;

    @BeforeEach
    void setUp() {
        monnify = (FakeMonnifyClient) monnifyClient;
        monnify.reset();
        orderPaymentApplication.reset();
        buyer = login("demo", "admin", "Demo1234!");
        buyerClientId = clientRepository.findBySlug("demo").orElseThrow().getId();
    }

    // ------------------------------------------------------------------------
    // (D1, D2) Success end to end - the order is paid only from a provider
    // response the SERVER fetched.
    // ------------------------------------------------------------------------

    @Test
    void aSignedWebhookVerifiesServerSideAndFulfilsTheOrderExactlyOnce() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK)
                .orElseThrow();

        assertThat(checkout.checkoutUrl()).isNotBlank();
        assertThat(checkout.paymentReference()).startsWith(fixture.orderNumber());
        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);

        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        assertThat(postWebhook(successBody(checkout), true).getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment settled = payment(checkout.paymentReference());
        assertThat(settled.getStatus()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(settled.getAmountPaid()).isEqualByComparingTo(ORDER_TOTAL);
        assertThat(settled.getVerifiedVia()).isEqualTo(PaymentVerificationSource.WEBHOOK);
        assertThat(settled.getPaidAt()).isNotNull();
        // The verify response, kept whole for dispute forensics.
        assertThat(settled.getProviderPayload()).contains("settlementAmount");

        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
    }

    /**
     * The webhook body is a TRIGGER, not evidence. Even correctly signed, its
     * claimed amount is ignored: the server asks Monnify itself and applies that.
     * Here the body lies about a 92,000 payment while Monnify says the transaction
     * failed - and the order must not be fulfilled.
     */
    @Test
    void aSignedWebhookClaimingSuccessIsIgnoredWhenTheProviderSaysOtherwise() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.FAILED, BigDecimal.ZERO);

        assertThat(postWebhook(successBody(checkout), true).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.FAILED);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).isEmpty();
        assertThat(incomingQuantity(fixture.productId())).isZero();
    }

    // ------------------------------------------------------------------------
    // (D3) IDEMPOTENCY - the single most important property in this module.
    // ------------------------------------------------------------------------

    /**
     * All three reporting paths, applied to the same successful payment, in the
     * order they race in real life: the webhook lands, the buyer's browser returns
     * and re-verifies, then the sweep re-checks. The order must advance once and
     * the incoming stock must go up once.
     */
    @Test
    void applyingTheSameSuccessRepeatedlyChangesTheOrderAndIncomingStockExactlyOnce() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        String body = successBody(checkout);

        // 1. The webhook.
        assertThat(postWebhook(body, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 2. The identical webhook again - Monnify retries, and replays happen.
        assertThat(postWebhook(body, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 3. And once more, for good measure.
        assertThat(postWebhook(body, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 4. The browser comes back and triggers a server-side re-verify.
        verify(checkout.paymentReference(), buyer, HttpStatus.OK);
        verify(checkout.paymentReference(), buyer, HttpStatus.OK);
        // 5. The reconciliation sweep re-checks everything still open.
        reconciliationService.reconcilePendingPayments();

        // Exactly one application, from six independent reports of the same payment.
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        // One attempt, still PAID, still credited to the path that actually won -
        // a later report must not overwrite verifiedVia.
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(fixture.orderId())).hasSize(1);
        Payment settled = payment(checkout.paymentReference());
        assertThat(settled.getStatus()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(settled.getVerifiedVia()).isEqualTo(PaymentVerificationSource.WEBHOOK);

        // Every callback is still recorded, including the ones that deliberately did
        // nothing - a replay must be diagnosable, not invisible.
        List<PaymentWebhookEvent> events = eventsFor(checkout.paymentReference());
        assertThat(events).hasSize(3);
        assertThat(events).allMatch(PaymentWebhookEvent::isSignatureValid);
        assertThat(events).allMatch(PaymentWebhookEvent::isProcessed);
        assertThat(events.stream().map(PaymentWebhookEvent::getProcessingNote))
                .containsExactlyInAnyOrder(
                        PaymentApplicationOutcome.APPLIED_PAID.name(),
                        PaymentApplicationOutcome.ALREADY_FINAL.name(),
                        PaymentApplicationOutcome.ALREADY_FINAL.name());
    }

    // ------------------------------------------------------------------------
    // (D5) Signature verification.
    // ------------------------------------------------------------------------

    @Test
    void aWebhookWithAnInvalidSignatureIsLoggedAndNotProcessed() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        ResponseEntity<Void> response = postWebhookWithSignature(successBody(checkout), "deadbeef");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Logged with signature_valid = false, and NOT processed.
        List<PaymentWebhookEvent> events = eventsFor(checkout.paymentReference());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().isSignatureValid()).isFalse();
        assertThat(events.getFirst().isProcessed()).isFalse();
        assertThat(events.getFirst().getProcessingNote()).contains("did not match");
        assertThat(events.getFirst().getPayload()).contains("SUCCESSFUL_TRANSACTION");

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).isEmpty();
        assertThat(incomingQuantity(fixture.productId())).isZero();
    }

    /** A missing header is refused just as firmly as a wrong one. */
    @Test
    void aWebhookWithNoSignatureHeaderIsRefused() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        assertThat(postWebhook(successBody(checkout), false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(eventsFor(checkout.paymentReference()).getFirst().getProcessingNote())
                .contains("no monnify-signature header");
        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);
    }

    /** A malformed body still has to leave evidence - payment_webhook_events.payload is NOT NULL JSONB. */
    @Test
    void anUnparseableWebhookBodyIsStillRecorded() {
        long before = webhookEventRepository.count();

        ResponseEntity<Void> response = postWebhookWithSignature("this is not json", "deadbeef");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhookEventRepository.count()).isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------------
    // (D4) The amount check.
    // ------------------------------------------------------------------------

    @Test
    void anUnderpaymentIsRecordedAndFlaggedButNeverFulfilled() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        // Monnify says PAID, but for one naira less than the order total.
        BigDecimal short1 = ORDER_TOTAL.subtract(new BigDecimal("1.00"));
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, short1);

        assertThat(postWebhook(successBody(checkout), true).getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment attempt = payment(checkout.paymentReference());
        assertThat(attempt.getStatus()).isEqualTo(PaymentProviderStatus.FAILED);
        // Recorded, not rounded away - the real figure is what a refund conversation needs.
        assertThat(attempt.getAmountPaid()).isEqualByComparingTo(short1);
        assertThat(attempt.getProviderPayload()).isNotBlank();

        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).isEmpty();
        assertThat(orderPaymentApplication.failuresFor(checkout.paymentReference()))
                .singleElement()
                .asString()
                .contains("Underpaid");

        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(incomingQuantity(fixture.productId())).isZero();
        assertThat(eventsFor(checkout.paymentReference()).getFirst().getProcessingNote())
                .isEqualTo(PaymentApplicationOutcome.UNDERPAID.name());
    }

    /**
     * BigDecimal.equals compares scale as well as value, so "92000.0" would not
     * equal "92000.00". The check uses compareTo; this pins that, because getting
     * it wrong rejects perfectly good payments over trailing-zero formatting.
     */
    @Test
    void anAmountThatDiffersOnlyInScaleIsStillAnExactMatch() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, new BigDecimal("92000.0"));

        assertThat(postWebhook(successBody(checkout), true).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // Failed / abandoned payments.
    // ------------------------------------------------------------------------

    /**
     * ABANDONED is kept distinct from FAILED - "the buyer walked away" is not "the
     * bank declined", and the order stays payable either way.
     */
    @Test
    void anAbandonedCheckoutEndsTheAttemptButLeavesTheOrderPayable() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.ABANDONED, null);
        verify(checkout.paymentReference(), buyer, HttpStatus.OK);

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.ABANDONED);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).isEmpty();

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // Still payable: the buyer can retry on the same order.
        initialize(fixture.orderId(), buyer, HttpStatus.OK);
    }

    // ------------------------------------------------------------------------
    // Expired checkout / retry.
    // ------------------------------------------------------------------------

    /**
     * Monnify expires a checkoutUrl after 40 minutes. Re-serving the stored one
     * lands the buyer on a provider error page, so a retry must mint a whole new
     * transaction with a new unique paymentReference.
     */
    @Test
    void retryingPaymentMintsAFreshReferenceRatherThanReusingAnExpiredCheckoutUrl() {
        Fixture fixture = newPayableOrder();

        InitializePaymentResponse first = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(first.transactionReference(), MonnifyTransactionStatus.EXPIRED, null);
        verify(first.paymentReference(), buyer, HttpStatus.OK);

        InitializePaymentResponse retry = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        assertThat(retry.paymentReference()).isNotEqualTo(first.paymentReference());
        assertThat(retry.transactionReference()).isNotEqualTo(first.transactionReference());
        assertThat(retry.checkoutUrl()).isNotEqualTo(first.checkoutUrl());
        assertThat(monnify.initCount()).isEqualTo(2);

        // Both attempts survive - the abandoned one is the evidence trail.
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(fixture.orderId())).hasSize(2);
        assertThat(payment(first.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.ABANDONED);
        assertThat(payment(retry.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);

        // And the retry can be paid normally.
        monnify.settle(retry.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);
        assertThat(postWebhook(successBody(retry), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
    }

    /** The double-click guard: a paid order must never open a second checkout. */
    @Test
    void anAlreadyPaidOrderCannotBePaidAgain() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);
        postWebhook(successBody(checkout), true);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                INITIALIZE_PATH,
                HttpMethod.POST,
                new HttpEntity<>(new InitializePaymentRequest(fixture.orderId()), authHeaders(buyer)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("already been paid");
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // (F) Cross-tenant isolation. payments has no client_id, so nothing else is
    // scoping these reads.
    // ------------------------------------------------------------------------

    @Test
    void anotherCompanyCanNeitherVerifyNorPayForThisOrder() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        TenantLoginResponse stranger = signup("Someone Else Ltd");

        // 404, not 403: distinguishing "not yours" from "does not exist" would turn
        // this endpoint into an oracle for whether a reference is real.
        ResponseEntity<ApiError> verifyResponse = restTemplate.exchange(
                "/api/payments/" + checkout.paymentReference() + "/verify",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(stranger)),
                ApiError.class);
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> initResponse = restTemplate.exchange(
                INITIALIZE_PATH,
                HttpMethod.POST,
                new HttpEntity<>(new InitializePaymentRequest(fixture.orderId()), authHeaders(stranger)),
                ApiError.class);
        assertThat(initResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Nothing leaked, and nothing changed.
        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(fixture.orderId())).hasSize(1);
    }

    @Test
    void verifyRequiresAuthentication() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/payments/" + checkout.paymentReference() + "/verify",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The buyer's own verify tells them the truth, and routes them to their order. */
    @Test
    void theOwningBuyerGetsAServerVerifiedResultForTheirOwnPayment() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);

        PaymentVerificationResponse verified = verify(checkout.paymentReference(), buyer, HttpStatus.OK);

        assertThat(verified.status()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(verified.orderId()).isEqualTo(fixture.orderId());
        assertThat(verified.orderNumber()).isEqualTo(fixture.orderNumber());
        assertThat(verified.orderPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(verified.amountPaid()).isEqualByComparingTo(ORDER_TOTAL);
        assertThat(payment(checkout.paymentReference()).getVerifiedVia())
                .isEqualTo(PaymentVerificationSource.RETURN_VERIFY);
    }

    // ------------------------------------------------------------------------
    // (D6) Reconciliation.
    // ------------------------------------------------------------------------

    /**
     * The disaster this module exists to prevent: Monnify took the money and the
     * webhook never arrived. Without the sweep the order sits at PENDING_PAYMENT
     * forever with the buyer's money gone.
     */
    @Test
    void theSweepRescuesAPaidOrderWhoseWebhookNeverArrived() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();

        // Monnify has the money. Nobody told us.
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, ORDER_TOTAL);
        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);

        reconciliationService.reconcilePendingPayments();

        Payment rescued = payment(checkout.paymentReference());
        assertThat(rescued.getStatus()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(rescued.getVerifiedVia()).isEqualTo(PaymentVerificationSource.RECONCILIATION);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);

        // And running it again changes nothing.
        reconciliationService.reconcilePendingPayments();
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
        assertThat(incomingQuantity(fixture.productId())).isEqualTo(1);
    }

    /** A provider outage must leave the attempt PENDING, never resolve it as failed. */
    @Test
    void aProviderOutageDuringTheSweepLeavesTheAttemptPendingForTheNextRun() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.failVerifyFor(checkout.transactionReference());

        reconciliationService.reconcilePendingPayments();

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);
        assertThat(orderPaymentApplication.failuresFor(checkout.paymentReference())).isEmpty();
    }

    // ------------------------------------------------------------------------
    // (A) Graceful degradation when Monnify is unconfigured.
    // ------------------------------------------------------------------------

    /**
     * The app starts and everything else keeps working; only card checkout says so,
     * with a clean typed error rather than a 500. Pay-on-delivery does not pass
     * through this module at all.
     */
    @Test
    void cardCheckoutDegradesToACleanErrorWhenMonnifyIsUnconfigured() {
        monnify.setConfigured(false);
        Fixture fixture = newPayableOrder();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                INITIALIZE_PATH,
                HttpMethod.POST,
                new HttpEntity<>(new InitializePaymentRequest(fixture.orderId()), authHeaders(buyer)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("pay on delivery");
        assertThat(paymentRepository.findAllByOrderIdOrderByCreatedAtDesc(fixture.orderId())).isEmpty();
    }

    /**
     * A callback that cannot be verified must be recorded and deferred, never
     * guessed at. The sweep picks it up once credentials return.
     */
    @Test
    void aWebhookArrivingWhileUnconfiguredIsRecordedAndDeferred() {
        Fixture fixture = newPayableOrder();
        InitializePaymentResponse checkout = initialize(fixture.orderId(), buyer, HttpStatus.OK).orElseThrow();
        monnify.setConfigured(false);

        assertThat(postWebhook(successBody(checkout), true).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(eventsFor(checkout.paymentReference()).getFirst().getProcessingNote())
                .contains("not configured");
        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PENDING);
    }


    // ------------------------------------------------------------------------
    // (D6) ONE CHECKOUT, SEVERAL ORDERS, ONE MONNIFY TRANSACTION
    // ------------------------------------------------------------------------

    /**
     * A multi-seller basket splits into one order per seller (V12) but is paid for ONCE.
     *
     * <p>The property this pins down is the one that costs real money if it breaks: the
     * amount sent to Monnify, and frozen on the payments row, is the GROUP total. If it
     * were the anchor order's own total, a buyer with three sellers in their basket would
     * be charged for one of them and fulfilled for all three.
     *
     * <p>It also pins the anchoring rule. Whichever member of the group the caller names,
     * the payment hangs off the group's FIRST order - so two tabs opened on two different
     * orders of one basket cannot produce two payment rows that each charge the full
     * basket.
     */
    @Test
    void oneCheckoutGroupOpensOneMonnifyTransactionForTheWholeGroupTotal() {
        SplitFixture split = newPayableCheckoutGroup();
        BigDecimal groupTotal = ORDER_TOTAL.add(ORDER_TOTAL);

        // Deliberately initialised against the SECOND order, not the first.
        InitializePaymentResponse checkout =
                initialize(split.secondOrderId(), buyer, HttpStatus.OK).orElseThrow();

        Payment opened = payment(checkout.paymentReference());
        assertThat(opened.getAmount())
                .as("the payment must cover the whole checkout, not just the order that was clicked")
                .isEqualByComparingTo(groupTotal);
        // Anchored on the group's first order regardless of which one was named.
        assertThat(opened.getOrder().getId()).isEqualTo(split.firstOrderId());
        assertThat(checkout.paymentReference()).startsWith(split.firstOrderNumber());

        // And the whole group settles off that single transaction.
        monnify.settle(checkout.transactionReference(), MonnifyTransactionStatus.PAID, groupTotal);
        assertThat(postWebhook(splitSuccessBody(checkout, groupTotal), true).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(payment(checkout.paymentReference()).getStatus()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference()).getFirst().amountPaid())
                .isEqualByComparingTo(groupTotal);

        // Replayed, exactly as Monnify retries: still one application.
        assertThat(postWebhook(splitSuccessBody(checkout, groupTotal), true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(orderPaymentApplication.successesFor(checkout.paymentReference())).hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------

    private record Fixture(UUID orderId, String orderNumber, UUID productId) {
    }

    /** Two orders from ONE checkout - what a two-seller basket produces. */
    private record SplitFixture(UUID firstOrderId, String firstOrderNumber, UUID secondOrderId) {
    }

    /**
     * A two-order checkout group, both awaiting payment, each for ORDER_TOTAL.
     *
     * <p>Written straight through the repositories because order creation - and the split
     * itself - belongs to the order module; this test is about what the PAYMENT module
     * does with a group that already exists. The two order numbers are minted in a fixed
     * lexical order so "the group's first order" is deterministic and the anchoring
     * assertion means something.
     */
    private SplitFixture newPayableCheckoutGroup() {
        String unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID checkoutGroupId = UUID.randomUUID();
        UUID sellerId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();

        TenantContext.set(buyerClientId);
        try {
            Order first = orderRepository.saveAndFlush(payableOrder("PP-SPLITA-" + unique, checkoutGroupId, sellerId));
            Order second = orderRepository.saveAndFlush(payableOrder("PP-SPLITB-" + unique, checkoutGroupId, sellerId));
            return new SplitFixture(first.getId(), first.getOrderNumber(), second.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private Order payableOrder(String orderNumber, UUID checkoutGroupId, UUID sellerId) {
        return Order.builder()
                .orderNumber(orderNumber)
                .sellerClientId(sellerId)
                .checkoutGroupId(checkoutGroupId)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentStatus(PaymentStatus.PENDING)
                .paymentMethod(PaymentMethod.MONNIFY)
                .currency("NGN")
                .subtotal(ORDER_TOTAL)
                .deliveryFee(BigDecimal.ZERO)
                .total(ORDER_TOTAL)
                .deliveryContactName("Demo Buyer")
                .deliveryContactPhone("+2348000000000")
                .build();
    }

    /** {@link #successBody} with an explicit amount, for the group total. */
    private String splitSuccessBody(InitializePaymentResponse checkout, BigDecimal amount) {
        return """
                {"eventType":"SUCCESSFUL_TRANSACTION","eventData":{"product":{"type":"WEB_SDK",\
                "reference":"%s"},"transactionReference":"%s","paymentReference":"%s",\
                "paidOn":"26/02/2020 09:38:13 AM","amountPaid":%s,"totalPayable":%s,\
                "paymentStatus":"PAID","paymentMethod":"CARD","currency":"NGN",\
                "customer":{"email":"buyer@example.com","name":"Demo Buyer"}}}"""
                .formatted(
                        checkout.paymentReference(),
                        checkout.transactionReference(),
                        checkout.paymentReference(),
                        amount,
                        amount);
    }

    /**
     * A Monnify order awaiting payment, plus the buyer's own product row that
     * incoming stock lands on. Written straight through the repositories because
     * order creation belongs to another module - this test is about payment, not
     * about how an order comes to exist.
     */
    private Fixture newPayableOrder() {
        String unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String orderNumber = "PP-PAYTEST-" + unique;

        // TenantAwareEntity.@PrePersist reads TenantContext; set it exactly the way
        // TenantResolutionFilter does for a real request.
        TenantContext.set(buyerClientId);
        try {
            Order order = orderRepository.saveAndFlush(Order.builder()
                    .orderNumber(orderNumber)
                    // NOT NULL since V11: every order names its seller. ProcurePal
                    // here, which is what these rows have always meant.
                    .sellerClientId(clientRepository.findByPlatformOwnerTrue().orElseThrow().getId())
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

            Product product = productRepository.saveAndFlush(Product.builder()
                    .name("Incoming stock probe " + unique)
                    .sku("PAYTEST-" + unique)
                    .unitPrice(ORDER_TOTAL)
                    .quantityOnHand(0)
                    .incomingQuantity(0)
                    .active(true)
                    .build());

            orderPaymentApplication.register(order.getId(), product.getId());
            return new Fixture(order.getId(), orderNumber, product.getId());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A SUCCESSFUL_TRANSACTION callback shaped like Monnify's documented envelope.
     * Its {@code amountPaid} is deliberately the full total in every test - the
     * point being that the server never reads it.
     */
    private String successBody(InitializePaymentResponse checkout) {
        return """
                {"eventType":"SUCCESSFUL_TRANSACTION","eventData":{"product":{"type":"WEB_SDK",\
                "reference":"%s"},"transactionReference":"%s","paymentReference":"%s",\
                "paidOn":"26/02/2020 09:38:13 AM","amountPaid":%s,"totalPayable":%s,\
                "paymentStatus":"PAID","paymentMethod":"CARD","currency":"NGN",\
                "customer":{"email":"buyer@example.com","name":"Demo Buyer"}}}"""
                .formatted(
                        checkout.paymentReference(),
                        checkout.transactionReference(),
                        checkout.paymentReference(),
                        ORDER_TOTAL,
                        ORDER_TOTAL);
    }

    private ResponseEntity<Void> postWebhook(String body, boolean sign) {
        return postWebhookWithSignature(body, sign ? signatureVerifier.computeSignature(body) : null);
    }

    private ResponseEntity<Void> postWebhookWithSignature(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set(SIGNATURE_HEADER, signature);
        }
        return restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    private java.util.Optional<InitializePaymentResponse> initialize(
            UUID orderId, TenantLoginResponse caller, HttpStatus expected) {
        ResponseEntity<InitializePaymentResponse> response = restTemplate.exchange(
                INITIALIZE_PATH,
                HttpMethod.POST,
                new HttpEntity<>(new InitializePaymentRequest(orderId), authHeaders(caller)),
                InitializePaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return java.util.Optional.ofNullable(response.getBody());
    }

    private PaymentVerificationResponse verify(
            String paymentReference, TenantLoginResponse caller, HttpStatus expected) {
        ResponseEntity<PaymentVerificationResponse> response = restTemplate.exchange(
                "/api/payments/" + paymentReference + "/verify",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)),
                PaymentVerificationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
    }

    private Payment payment(String paymentReference) {
        return paymentRepository.findByPaymentReference(paymentReference).orElseThrow();
    }

    private int incomingQuantity(UUID productId) {
        return productRepository.findById(productId).orElseThrow().getIncomingQuantity();
    }

    private List<PaymentWebhookEvent> eventsFor(String paymentReference) {
        return webhookEventRepository.findAll().stream()
                .filter(event -> paymentReference.equals(event.getPaymentReference()))
                .toList();
    }

    private TenantLoginResponse login(String clientIdentifier, String username, String password) {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(clientIdentifier, username, password), TenantLoginResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(
                        name + " " + unique.substring(0, 8),
                        null,
                        "owner-" + unique + "@example.com",
                        PASSWORD,
                        PASSWORD),
                TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
