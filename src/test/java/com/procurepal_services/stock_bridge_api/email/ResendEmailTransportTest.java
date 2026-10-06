package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.procurepal_services.stock_bridge_api.email.preferences.UnsubscribeProperties;
import com.procurepal_services.stock_bridge_api.email.preferences.UnsubscribeTokenService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The Resend request shape, against a MockRestServiceServer so the assertions are on
 * the JSON that would actually leave the process. EmailSender's policy - who gets
 * what, and that nothing throws - is EmailSenderTest's, and holds for either provider.
 */
class ResendEmailTransportTest {

    private static final String BASE_URL = "https://resend.test";

    private static final EmailProperties EMAIL = new EmailProperties(
            true, null, "no-reply@send.procurepaddy.test", "Procure Paddy",
            "support@procurepaddy.test", null, "https://app.procurepaddy.test", null, null);

    private static final ResendProperties RESEND =
            new ResendProperties("re_test_key", BASE_URL, null, null, null, null);

    private static final EmailMessage MESSAGE = new EmailMessage(
            List.of("buyer@example.com", "finance@example.com"), "Order PP-1 confirmed", "<p>₦5,000</p>",
            "₦5,000");

    private static final UnsubscribeTokenService NO_UNSUBSCRIBE =
            new UnsubscribeTokenService(new UnsubscribeProperties("  ", "  "));

    private MockRestServiceServer server;

    private ResendEmailTransport transport(EmailProperties email, ResendProperties resend) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new ResendEmailTransport(resend, email, builder);
    }

    @Test
    void postsTheMessageWithTheApiKeyAndReturnsResendsId() throws Exception {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        server.expect(once(), requestTo(BASE_URL + "/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.from").value("\"Procure Paddy\" <no-reply@send.procurepaddy.test>"))
                .andExpect(jsonPath("$.to[0]").value("buyer@example.com"))
                .andExpect(jsonPath("$.to[1]").value("finance@example.com"))
                .andExpect(jsonPath("$.subject").value("Order PP-1 confirmed"))
                // Non-ASCII survives - the Naira sign is the canary, as it is for SES.
                .andExpect(jsonPath("$.html").value("<p>₦5,000</p>"))
                .andExpect(jsonPath("$.text").value("₦5,000"))
                .andExpect(jsonPath("$.reply_to").value("support@procurepaddy.test"))
                .andExpect(jsonPath("$.tags[0].name").value("kind"))
                .andExpect(jsonPath("$.tags[0].value").value("transactional"))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(transport.send(MESSAGE, null)).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        server.verify();
    }

    /** A receipt must never carry an unsubscribe button - see EmailSender. */
    @Test
    void transactionalMailCarriesNoCustomHeaders() throws Exception {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(jsonPath("$.headers").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        transport.send(MESSAGE, null);
        server.verify();
    }

    @Test
    void promotionalMailCarriesTheRfc8058Headers() throws Exception {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        EmailMessage promotional = new EmailMessage(
                List.of("buyer@example.com"), "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe']")
                        .value("<https://api.procurepaddy.test/api/email/unsubscribe?token=t>"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe-Post']").value("List-Unsubscribe=One-Click"))
                .andExpect(jsonPath("$.tags[0].value").value("promotional"))
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        transport.send(promotional, "https://api.procurepaddy.test/api/email/unsubscribe?token=t");
        server.verify();
    }

    /** Resend rejects "reply_to": null, so an unset one must be absent rather than null. */
    @Test
    void omitsUnsetOptionalFields() throws Exception {
        EmailProperties minimal = new EmailProperties(
                true, null, "no-reply@send.procurepaddy.test", null, "  ", null, null, null, null);
        ResendEmailTransport transport = transport(minimal, RESEND);
        EmailMessage htmlOnly = new EmailMessage(List.of("buyer@example.com"), "Hi", "<p>Hi</p>", null);
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(jsonPath("$.from").value("no-reply@send.procurepaddy.test"))
                .andExpect(jsonPath("$.reply_to").doesNotExist())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        transport.send(htmlOnly, null);
        server.verify();
    }

    /**
     * A 429 is a request to wait, not a failed email. The retry must carry the SAME
     * Idempotency-Key, which is what makes it safe: if the first attempt was in fact
     * accepted and only its response was lost, Resend answers the repeat with the
     * original result instead of delivering twice.
     */
    @Test
    void retriesARateLimitWithTheSameIdempotencyKey() throws Exception {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        List<String> keys = new ArrayList<>();
        HttpHeaders retryNow = new HttpHeaders();
        retryNow.set(HttpHeaders.RETRY_AFTER, "0");
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryNow)
                        .body("{\"statusCode\":429,\"message\":\"Too many requests\"}"));
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"after-retry\"}", MediaType.APPLICATION_JSON));

        assertThat(transport.send(MESSAGE, null)).isEqualTo("after-retry");
        server.verify();
        assertThat(keys).hasSize(2).doesNotContainNull();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    /** An unverified domain does not become verified by asking again. */
    @Test
    void doesNotRetryAClientErrorAndReportsResendsMessage() {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        server.expect(once(), requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":403,\"message\":\"The send.procurepaddy.test domain is not "
                                + "verified.\",\"name\":\"validation_error\"}"));

        assertThatThrownBy(() -> transport.send(MESSAGE, null))
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("domain is not verified");
        server.verify();
    }

    @Test
    void givesUpOnAPersistentServerErrorAfterTheLastAttempt() {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        HttpHeaders retryNow = new HttpHeaders();
        retryNow.set(HttpHeaders.RETRY_AFTER, "0");
        server.expect(times(ResendEmailTransport.MAX_ATTEMPTS), requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).headers(retryNow));

        assertThatThrownBy(() -> transport.send(MESSAGE, null)).hasMessageContaining("HTTP 502");
        server.verify();
    }

    @Test
    void isUnconfiguredWithoutAnApiKey() {
        ResendProperties noKey = new ResendProperties("  ", null, null, null, null, null);

        assertThat(transport(EMAIL, noKey).isConfigured()).isFalse();
        assertThat(transport(EMAIL, RESEND).isConfigured()).isTrue();
        assertThat(noKey.baseUrl()).isEqualTo(ResendProperties.DEFAULT_BASE_URL);
    }

    /** End to end through EmailSender: a Resend failure is a false, never an exception. */
    @Test
    void emailSenderAbsorbsAResendFailure() {
        ResendEmailTransport transport = transport(EMAIL, RESEND);
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"API key is invalid\"}"));
        EmailSender sender = new EmailSender(EMAIL, transport, NO_UNSUBSCRIBE);

        assertThat(sender.send(MESSAGE)).isFalse();
    }

    @Test
    void emailSenderIsUnconfiguredWhenResendHasNoKey() {
        ResendEmailTransport transport = transport(EMAIL, new ResendProperties(null, null, null, null, null, null));
        EmailSender sender = new EmailSender(EMAIL, transport, NO_UNSUBSCRIBE);

        assertThat(sender.isConfigured()).isFalse();
        // Logged and dropped, with no request made - MockRestServiceServer would fail
        // the verify below on any unexpected call.
        assertThat(sender.send(MESSAGE)).isFalse();
        server.verify();
    }
}
