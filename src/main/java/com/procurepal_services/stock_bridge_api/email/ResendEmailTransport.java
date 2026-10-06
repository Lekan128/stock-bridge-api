package com.procurepal_services.stock_bridge_api.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Resend (resend.com) as the mail provider: {@code POST https://api.resend.com/emails}
 * with a bearer API key. The default provider - {@code app.email.provider} unset or
 * {@code resend}.
 *
 * <h2>No SDK</h2>
 * Resend publishes a Java SDK, and it is not used. The whole integration is one JSON
 * POST, the codebase already talks to Monnify the same way through {@link RestClient},
 * and a vendor SDK would bring its own HTTP client and JSON stack for the sake of
 * saving thirty lines.
 *
 * <h2>Retries, and why they are safe</h2>
 * Resend rate-limits per team (a handful of requests per second by default), and the
 * email executor can run several sends at once - an order produces a buyer receipt and
 * an operator notice in the same instant. A 429 there is not a failure of the mail,
 * it is a request to wait, so it is retried, as is a 5xx. Every attempt for one
 * message carries the same {@code Idempotency-Key}, so a retry after a response that
 * was lost in transit cannot deliver the message twice: Resend answers the repeat
 * with the original result. Attempts are bounded and the waits are short because
 * this runs on one of five executor threads, and a send that cannot get through in a
 * few seconds is better logged as failed than allowed to queue everything behind it.
 * A 4xx other than 429 - a bad key, an unverified domain, a malformed address - will
 * not improve with time and is not retried.
 */
@Component
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "resend", matchIfMissing = true)
@Slf4j
public class ResendEmailTransport implements EmailTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The first attempt plus two retries. */
    static final int MAX_ATTEMPTS = 3;

    /** Ceiling on any one wait, whatever Retry-After asks for. See the class doc. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);

    private final ResendProperties resendProperties;
    private final EmailProperties emailProperties;
    private final RestClient restClient;

    @Autowired
    public ResendEmailTransport(ResendProperties resendProperties, EmailProperties emailProperties) {
        this(resendProperties, emailProperties, defaultBuilder(resendProperties));
    }

    /** For tests, which bind a MockRestServiceServer to the builder. */
    ResendEmailTransport(
            ResendProperties resendProperties, EmailProperties emailProperties, RestClient.Builder builder) {
        this.resendProperties = resendProperties;
        this.emailProperties = emailProperties;
        this.restClient = builder.baseUrl(resendProperties.baseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder(ResendProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Override
    public String name() {
        return "resend";
    }

    @Override
    public boolean isConfigured() {
        return resendProperties.hasApiKey();
    }

    @Override
    public String send(EmailMessage message, String unsubscribeUrl) throws Exception {
        String body = MAPPER.writeValueAsString(buildRequest(message, unsubscribeUrl));
        String idempotencyKey = UUID.randomUUID().toString();

        for (int attempt = 1; ; attempt++) {
            try {
                String response = restClient.post()
                        .uri("/emails")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendProperties.apiKey().trim())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return messageId(response);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Resend returned HTTP " + status + ": " + errorMessage(e.getResponseBodyAsString()), e);
                }
                Duration wait = backoff(e.getResponseHeaders());
                log.info("Resend returned HTTP {} for \"{}\"; retrying in {} ms (attempt {} of {})",
                        status, message.subject(), wait.toMillis(), attempt + 1, MAX_ATTEMPTS);
                try {
                    Thread.sleep(wait.toMillis());
                } catch (InterruptedException interrupted) {
                    // Shutdown. Give up on this message and leave the flag set for
                    // the executor, rather than swallowing it.
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    /**
     * The JSON body. A map rather than a DTO so absent optional fields are simply not
     * keys - Resend treats {@code "reply_to": null} as a validation error, not as
     * "unset".
     */
    private Map<String, Object> buildRequest(EmailMessage message, String unsubscribeUrl) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("from", emailProperties.formattedFrom());
        request.put("to", message.to());
        request.put("subject", message.subject());
        if (notBlank(message.htmlBody())) {
            request.put("html", message.htmlBody());
        }
        if (notBlank(message.textBody())) {
            request.put("text", message.textBody());
        }
        if (notBlank(emailProperties.replyToAddress())) {
            request.put("reply_to", emailProperties.replyToAddress().trim());
        }
        if (unsubscribeUrl != null) {
            // The same two RFC 8058 headers SES sends, and the same rule: only on
            // PROMOTIONAL mail, never on a receipt. See EmailSender.
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(EmailSender.LIST_UNSUBSCRIBE_HEADER, EmailSender.listUnsubscribeValue(unsubscribeUrl));
            headers.put(EmailSender.LIST_UNSUBSCRIBE_POST_HEADER, EmailSender.ONE_CLICK);
            request.put("headers", headers);
        }
        // Echoed back on every webhook event and filterable in the Resend dashboard,
        // so "were the bounces receipts or verification mail" is answerable there.
        request.put("tags", List.of(Map.of("name", "kind", "value", message.kind().name().toLowerCase(Locale.ROOT))));
        return request;
    }

    private static String messageId(String responseBody) {
        try {
            JsonNode id = MAPPER.readTree(responseBody == null ? "" : responseBody).get("id");
            return id == null ? null : id.asText();
        } catch (Exception e) {
            // Resend said 2xx, so the mail is accepted; an unreadable body costs us
            // only the id in a debug log line.
            return null;
        }
    }

    /** Resend's error body is {@code {"statusCode":..,"message":"..","name":".."}}; the message is the useful part. */
    private static String errorMessage(String responseBody) {
        try {
            JsonNode message = MAPPER.readTree(responseBody).get("message");
            if (message != null && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return responseBody == null || responseBody.isBlank() ? "(no body)" : responseBody;
    }

    private static Duration backoff(HttpHeaders headers) {
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                Duration requested = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                return requested.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : requested;
            } catch (NumberFormatException ignored) {
                // An HTTP-date Retry-After; not worth parsing for a capped wait.
            }
        }
        return DEFAULT_BACKOFF;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
