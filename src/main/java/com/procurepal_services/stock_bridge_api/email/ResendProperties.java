package com.procurepal_services.stock_bridge_api.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend (resend.com) - the mail provider while SES production access is pending.
 * Only read when {@code app.email.provider} is {@code resend} (the default); see
 * {@link ResendEmailTransport} and, for bounces and complaints,
 * {@code ResendWebhookService}.
 *
 * <p>Same discipline as {@link EmailProperties}: everything binds blank rather than
 * failing placeholder resolution, and a blank {@link #apiKey} means "email
 * unavailable" - mail is logged and dropped exactly as a blank from-address is -
 * rather than a startup failure.
 *
 * @param apiKey a Resend API key ({@code re_...}). Use a "Sending access" key
 *     restricted to the one domain this environment sends from, so a leaked staging
 *     key cannot send as production. Blank leaves email unconfigured.
 * @param baseUrl the API origin. Overridable only so tests can point it at a mock.
 * @param connectTimeout bound on connecting to Resend. Sends run on the email
 *     executor after the caller's transaction has committed, so this is never request
 *     latency - it is how long a stuck send holds one of the executor's threads.
 * @param readTimeout bound on waiting for Resend's response.
 * @param webhookSecret the signing secret ({@code whsec_...}) of the webhook endpoint
 *     configured in the Resend dashboard. Blank means bounce and complaint webhooks
 *     are refused (401) rather than trusted - there is no "unsigned is fine" mode.
 * @param webhookTolerance how far a webhook's {@code svix-timestamp} may be from now.
 *     Svix's own libraries use five minutes; this is the replay window.
 */
@ConfigurationProperties(prefix = "app.email.resend")
public record ResendProperties(
        String apiKey,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String webhookSecret,
        Duration webhookTolerance) {

    public static final String DEFAULT_BASE_URL = "https://api.resend.com";

    public ResendProperties {
        baseUrl = notBlank(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        webhookTolerance = webhookTolerance == null ? Duration.ofMinutes(5) : webhookTolerance;
    }

    public boolean hasApiKey() {
        return notBlank(apiKey);
    }

    public boolean hasWebhookSecret() {
        return notBlank(webhookSecret);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
