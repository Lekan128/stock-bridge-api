package com.procurepal_services.stock_bridge_api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitCommand;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitResult;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * The wire level: endpoint paths, the response envelope, token caching, the 401
 * retry, and URL encoding. Everything the flow tests fake away is pinned here.
 *
 * <p>No network and no HTTP server - a stub {@link ClientHttpRequestFactory}
 * hands back canned responses and records what was actually sent, so the
 * assertions are on the real request the real code built.
 *
 * <p>All response bodies below are copied from Monnify's published samples.
 */
class MonnifyRestClientTest {

    private static final String API_KEY = "MK_TEST_GC3B8XG2XX";
    private static final String SECRET_KEY = "A663NRZA544DDPEM7KDN7Z8HRV6YXD8S";

    /** Monnify's own sample reference. The pipes are the whole reason encoding matters. */
    private static final String TRANSACTION_REFERENCE = "MNFY|20200226093601|002095";

    private static final String LOGIN_OK = """
            {"requestSuccessful":true,"responseMessage":"success","responseCode":"0",
             "responseBody":{"accessToken":"token-one","expiresIn":3599}}""";

    private static final String INIT_OK = """
            {"requestSuccessful":true,"responseMessage":"success","responseCode":"0",
             "responseBody":{"transactionReference":"MNFY|20190915200044|000090",
                             "paymentReference":"PP-2026-000123-ABCD",
                             "merchantName":"Test Limited",
                             "enabledPaymentMethod":["ACCOUNT_TRANSFER","CARD"],
                             "checkoutUrl":"https://sandbox.sdk.monnify.com/checkout/MNFY|20190915200044|000090"}}""";

    private static final String VERIFY_PAID = """
            {"requestSuccessful":true,"responseMessage":"success","responseCode":"0",
             "responseBody":{"transactionReference":"MNFY|20200226093601|002095",
                             "paymentReference":"PP-2026-000123-ABCD",
                             "amountPaid":"92000.00","totalPayable":"92000.00",
                             "settlementAmount":"91800.00","paidOn":"26/02/2020 09:38:13 AM",
                             "paymentStatus":"PAID","currency":"NGN","paymentMethod":"ACCOUNT_TRANSFER",
                             "customer":{"email":"buyer@example.com","name":"Demo Buyer"}}}""";

    private final StubRequestFactory requestFactory = new StubRequestFactory();

    // ------------------------------------------------------------------------
    // Endpoint paths and field mapping
    // ------------------------------------------------------------------------

    @Test
    void authenticatesWithBasicApiKeyAndSecretThenPostsToTheDocumentedInitPath() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.OK, INIT_OK);

        MonnifyInitResult result = client().initializeTransaction(new MonnifyInitCommand(
                "PP-2026-000123-ABCD",
                new BigDecimal("92000.00"),
                "NGN",
                "Demo Buyer",
                "buyer@example.com",
                "ProcurePal order PP-2026-000123"));

        MockClientHttpRequest login = requestFactory.requests().get(0);
        assertThat(login.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(login.getURI().getPath()).isEqualTo("/api/v1/auth/login");
        assertThat(login.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Basic " + Base64.getEncoder()
                        .encodeToString((API_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8)));

        MockClientHttpRequest init = requestFactory.requests().get(1);
        assertThat(init.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(init.getURI().getPath()).isEqualTo("/api/v1/merchant/transactions/init-transaction");
        assertThat(init.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-one");
        // The documented request fields, including the contract code and redirect
        // URL that come from configuration rather than from the caller.
        assertThat(init.getBodyAsString())
                .contains("\"paymentReference\":\"PP-2026-000123-ABCD\"")
                .contains("\"amount\":92000.00")
                .contains("\"customerName\":\"Demo Buyer\"")
                .contains("\"customerEmail\":\"buyer@example.com\"")
                .contains("\"currencyCode\":\"NGN\"")
                .contains("\"contractCode\":\"5867418298\"")
                .contains("\"redirectUrl\":\"http://localhost:5173/checkout/return\"");

        assertThat(result.checkoutUrl()).startsWith("https://sandbox.sdk.monnify.com/checkout/");
        assertThat(result.transactionReference()).isEqualTo("MNFY|20190915200044|000090");
        assertThat(result.paymentReference()).isEqualTo("PP-2026-000123-ABCD");
    }

    /**
     * The single most breakable line in the client. An unencoded pipe makes the
     * request path invalid and Monnify answers 404 - which reads exactly like "no
     * such transaction", i.e. a paid order silently treated as unpaid. A
     * double-encoded one (%257C) fails the same way.
     */
    @Test
    void urlEncodesThePipesInATransactionReferenceExactlyOnce() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.OK, VERIFY_PAID);

        client().getTransactionStatus(TRANSACTION_REFERENCE);

        MockClientHttpRequest verify = requestFactory.requests().get(1);
        assertThat(verify.getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(verify.getURI().toString())
                .isEqualTo("https://sandbox.monnify.com/api/v2/transactions/MNFY%7C20200226093601%7C002095");
        assertThat(verify.getURI().toString()).doesNotContain("%257C");
        // getPath() decodes, which is how we know it round-trips to the original.
        assertThat(verify.getURI().getPath()).isEqualTo("/api/v2/transactions/" + TRANSACTION_REFERENCE);
    }

    @Test
    void mapsTheVerifyResponseIncludingStringAmountsAndLagosLocalTimestamps() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.OK, VERIFY_PAID);

        MonnifyTransactionStatus status = client().getTransactionStatus(TRANSACTION_REFERENCE);

        assertThat(status.paymentStatus()).isEqualTo("PAID");
        assertThat(status.indicatesMoneyReceived()).isTrue();
        assertThat(status.paymentReference()).isEqualTo("PP-2026-000123-ABCD");
        assertThat(status.paymentMethod()).isEqualTo("ACCOUNT_TRANSFER");
        // Monnify sends amounts as JSON strings. compareTo, because the scale of a
        // BigDecimal parsed from "92000.00" is not the scale of new BigDecimal(92000).
        assertThat(status.amountPaid()).isEqualByComparingTo("92000.00");
        assertThat(status.totalPayable()).isEqualByComparingTo("92000.00");
        // 09:38:13 in Lagos is 08:38:13Z. Reading it as UTC would file every West
        // African payment an hour early.
        assertThat(status.paidOn().toInstant().toString()).isEqualTo("2020-02-26T08:38:13Z");
        // Kept verbatim for payments.provider_payload, including the fields we do
        // not model.
        assertThat(status.rawPayload()).contains("settlementAmount").contains("customer");
    }

    // ------------------------------------------------------------------------
    // Token caching and the 401 retry
    // ------------------------------------------------------------------------

    /** Fetching a token per request would triple call volume and add a round trip to every checkout. */
    @Test
    void logsInOnceAndReusesTheCachedTokenAcrossCalls() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.OK, VERIFY_PAID);
        requestFactory.enqueue(HttpStatus.OK, VERIFY_PAID);

        MonnifyRestClient client = client();
        client.getTransactionStatus(TRANSACTION_REFERENCE);
        client.getTransactionStatus(TRANSACTION_REFERENCE);

        assertThat(requestFactory.requests()).hasSize(3);
        assertThat(loginCount()).isEqualTo(1);
    }

    /** A revoked token or rotated credentials must self-heal, not wedge the instance until a restart. */
    @Test
    void discardsTheCachedTokenAndRetriesOnceAfterA401() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.UNAUTHORIZED, "{\"requestSuccessful\":false,\"responseMessage\":\"expired\"}");
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK.replace("token-one", "token-two"));
        requestFactory.enqueue(HttpStatus.OK, VERIFY_PAID);

        MonnifyTransactionStatus status = client().getTransactionStatus(TRANSACTION_REFERENCE);

        assertThat(status.paymentStatus()).isEqualTo("PAID");
        assertThat(loginCount()).isEqualTo(2);
        assertThat(requestFactory.requests().get(3).getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-two");
    }

    /** Exactly once - a genuinely bad credential must fail fast rather than loop. */
    @Test
    void givesUpAfterASecond401RatherThanLooping() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.UNAUTHORIZED, "{}");
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.UNAUTHORIZED, "{}");

        assertThatThrownBy(() -> client().getTransactionStatus(TRANSACTION_REFERENCE))
                .isInstanceOf(MonnifyApiException.class)
                .hasMessageContaining("401");
    }

    // ------------------------------------------------------------------------
    // Failure modes
    // ------------------------------------------------------------------------

    /**
     * Monnify answers HTTP 200 with {@code requestSuccessful:false} for some
     * business failures. Checking the status code alone would read a refusal as a
     * success - and on the verify path, that means treating an unknown transaction
     * as a settled one.
     */
    @Test
    void treatsA200WithRequestSuccessfulFalseAsAFailure() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueue(HttpStatus.OK,
                "{\"requestSuccessful\":false,\"responseMessage\":\"Transaction not found\",\"responseCode\":\"99\"}");

        assertThatThrownBy(() -> client().getTransactionStatus(TRANSACTION_REFERENCE))
                .isInstanceOf(MonnifyApiException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    void wrapsATransportFailureAsMonnifyApiExceptionRatherThanLettingItEscape() {
        requestFactory.enqueue(HttpStatus.OK, LOGIN_OK);
        requestFactory.enqueueIoFailure();

        assertThatThrownBy(() -> client().getTransactionStatus(TRANSACTION_REFERENCE))
                .isInstanceOf(MonnifyApiException.class);
    }

    /** Graceful degradation: no credentials means a typed error, never a NullPointerException at the socket. */
    @Test
    void refusesToCallAnythingWhenUnconfigured() {
        MonnifyRestClient unconfigured = new MonnifyRestClient(
                properties("", ""), RestClient.builder().requestFactory(requestFactory).build());

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.getTransactionStatus(TRANSACTION_REFERENCE))
                .isInstanceOf(MonnifyNotConfiguredException.class);
        assertThat(requestFactory.requests()).isEmpty();
    }

    /**
     * Regression guard. This class has two constructors, so the production one needs
     * an explicit @Autowired for Spring to pick it - and every other test here (and
     * in the integration tests) substitutes the bean, so nothing else would notice it
     * going missing until the application failed to start. That is exactly how it
     * broke once already.
     */
    @Test
    void theProductionConstructorIsUsableAndUnambiguous() {
        assertThat(new MonnifyRestClient(properties(API_KEY, SECRET_KEY)).isConfigured()).isTrue();

        long autowiredConstructors = Arrays.stream(MonnifyRestClient.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
        assertThat(autowiredConstructors)
                .as("exactly one constructor must be marked @Autowired, or Spring cannot instantiate the bean")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------

    private MonnifyRestClient client() {
        return new MonnifyRestClient(
                properties(API_KEY, SECRET_KEY),
                RestClient.builder()
                        .baseUrl("https://sandbox.monnify.com")
                        .requestFactory(requestFactory)
                        .build());
    }

    private long loginCount() {
        return requestFactory.requests().stream()
                .filter(request -> request.getURI().getPath().equals("/api/v1/auth/login"))
                .count();
    }

    private static MonnifyProperties properties(String apiKey, String secretKey) {
        return new MonnifyProperties(
                apiKey,
                secretKey,
                "5867418298",
                "https://sandbox.monnify.com",
                "http://localhost:5173/checkout/return",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                true,
                null);
    }

    /**
     * Canned responses in FIFO order, plus a record of every request the client
     * actually built. Preferred over MockRestServiceServer because the token cache
     * makes the number and order of calls the thing under test, and expectation-
     * based stubbing obscures exactly that.
     */
    private static final class StubRequestFactory implements ClientHttpRequestFactory {

        private final Deque<Object> responses = new ArrayDeque<>();
        private final List<MockClientHttpRequest> requests = new ArrayList<>();

        void enqueue(HttpStatus status, String body) {
            MockClientHttpResponse response =
                    new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            responses.add(response);
        }

        void enqueueIoFailure() {
            responses.add(new IOException("connection reset"));
        }

        List<MockClientHttpRequest> requests() {
            return requests;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            Object next = responses.poll();
            MockClientHttpRequest request = next instanceof IOException failure
                    ? new MockClientHttpRequest(httpMethod, uri) {
                        @Override
                        protected ClientHttpResponse executeInternal() throws IOException {
                            throw failure;
                        }
                    }
                    : new MockClientHttpRequest(httpMethod, uri);
            if (next instanceof MockClientHttpResponse response) {
                request.setResponse(response);
            }
            requests.add(request);
            return request;
        }
    }
}
