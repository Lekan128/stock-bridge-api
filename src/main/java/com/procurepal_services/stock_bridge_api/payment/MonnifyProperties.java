package com.procurepal_services.stock_bridge_api.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Monnify credentials and tuning. Every field binds from an env var via
 * application.yml; nothing here is ever hardcoded in Java, including in tests.
 *
 * <p>Like {@link com.procurepal_services.stock_bridge_api.storage.AwsProperties},
 * the credential fields default to blank rather than failing placeholder
 * resolution, so an unconfigured deployment still starts. {@link #isConfigured()}
 * is the single source of truth for whether card payment is usable - see
 * {@link MonnifyRestClient} for the graceful-degradation behaviour that hangs off
 * it. Pay-on-delivery is unaffected by any of this.
 *
 * @param requireWebhookSignature whether an unsigned callback is refused.
 *     <b>Defaults to true and must stay true in production.</b> It exists because
 *     Monnify documents that {@code monnify-signature} "is only included on
 *     webhook notifications sent in production, it is not present on sandbox
 *     notifications" - so sandbox webhook testing is impossible without a way to
 *     relax this. Relaxing it is far less dangerous here than it looks: an
 *     accepted callback is only ever a TRIGGER, never evidence. The payment is
 *     applied from a transaction status this server fetched from Monnify itself
 *     (see {@link MonnifyPaymentService}), so a forged callback can at worst make
 *     us ask Monnify about a reference, and Monnify answers with the truth.
 * @param tokenRefreshMargin how far before the provider-stated expiry the cached
 *     bearer token is discarded. Monnify tokens last an hour; refreshing at ~50
 *     minutes keeps a long-running request from starting with 9 seconds of token
 *     left.
 */
@ConfigurationProperties(prefix = "app.monnify")
public record MonnifyProperties(
        String apiKey,
        String secretKey,
        String contractCode,
        String baseUrl,
        String redirectUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration tokenRefreshMargin,
        boolean requireWebhookSignature,
        Reconciliation reconciliation) {

    /**
     * @param pendingPaymentGrace how stale a PENDING attempt must be before the
     *     sweep re-verifies it. Short enough that a dropped webhook is caught
     *     while the buyer is still watching, long enough not to race a checkout
     *     the buyer is mid-way through.
     * @param abandonedCheckoutGrace how long an order may sit at PENDING_PAYMENT
     *     before it is cancelled outright.
     */
    public record Reconciliation(
            boolean enabled, Duration interval, Duration pendingPaymentGrace, Duration abandonedCheckoutGrace) {

        public Reconciliation {
            interval = interval != null ? interval : Duration.ofMinutes(5);
            pendingPaymentGrace = pendingPaymentGrace != null ? pendingPaymentGrace : Duration.ofMinutes(10);
            abandonedCheckoutGrace = abandonedCheckoutGrace != null ? abandonedCheckoutGrace : Duration.ofHours(24);
        }
    }

    /**
     * Defaults applied here rather than only in application.yml so the record is
     * safe to construct directly in a unit test without every knob being spelled
     * out. Blank credentials are left blank on purpose - that is the signal
     * isConfigured() reads.
     */
    public MonnifyProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(10);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(30);
        tokenRefreshMargin = tokenRefreshMargin != null ? tokenRefreshMargin : Duration.ofMinutes(10);
        reconciliation = reconciliation != null ? reconciliation : new Reconciliation(true, null, null, null);
    }

    /**
     * All four are required together. A half-configured Monnify is treated exactly
     * like an absent one: card checkout returns a clean 503 and the rest of the
     * app is untouched. redirectUrl is excluded deliberately - Monnify accepts an
     * init-transaction without one, and a missing return URL degrades the buyer's
     * experience rather than breaking the payment.
     */
    public boolean isConfigured() {
        return notBlank(apiKey) && notBlank(secretKey) && notBlank(contractCode) && notBlank(baseUrl);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
