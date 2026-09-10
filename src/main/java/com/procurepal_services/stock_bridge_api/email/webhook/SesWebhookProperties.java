package com.procurepal_services.stock_bridge_api.email.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the SES bounce/complaint webhook can be told. Bound under
 * {@code app.email.sns} rather than added to
 * {@link com.procurepal_services.stock_bridge_api.email.EmailProperties}, for the
 * reason {@code EmailVerificationProperties} and {@code UnsubscribeProperties} give
 * for the same choice: that record answers "can this deploy send mail at all" and
 * every field on it feeds {@code isConfigured()} or the SES request itself. None of
 * these do. Widening it would also have broken every module that constructs it.
 *
 * <h2>Two of these are security controls and both default to the safe value</h2>
 * {@link #requireSignature()} and {@link #topicArn()} are the only things standing
 * between a public URL and an attacker who wants to unverify every customer on the
 * platform. They are boxed and defaulted in the compact constructor rather than
 * left to {@code application.yml}, so that a deploy which never sets them - or a
 * test that constructs this record directly, or a future config file that forgets a
 * key - fails <em>secure</em> rather than open. {@code MonnifyProperties} uses a
 * primitive for its equivalent flag and relies on the YAML default; that works, but
 * it means "absent" and "false" are the same value at the Java level, and for a
 * check this consequential that is the wrong direction to be ambiguous in.
 *
 * @param requireSignature whether a notification whose SNS signature does not verify
 *     is refused. <b>Defaults to true and must stay true everywhere, including
 *     staging.</b> This is the direct analogue of {@code
 *     app.monnify.require-webhook-signature}, and the analogy is worth stating
 *     precisely because the risk is <em>not</em> the same. Relaxing the Monnify flag
 *     is survivable: a callback there is only a trigger, and the payment is applied
 *     from a transaction status the server fetches from Monnify itself, so a forged
 *     callback can at most make us ask a question. There is no equivalent
 *     safety net here. SES has no "tell me again whether that address bounced" API
 *     that this code could consult, so the notification body IS the evidence, and a
 *     forged one is acted on directly: it suppresses an address and clears
 *     {@code is_email_verified} on every account holding it, across every tenant.
 *     With this false, anybody who discovers the URL can silence the order receipts
 *     of the entire customer base with a loop and a list of addresses. It exists at
 *     all only so that a local developer can POST a fixture by hand; there is no
 *     production or staging scenario in which turning it off is acceptable.
 * @param topicArn the exact SNS topic ARN this endpoint accepts notifications from.
 *     <b>Required in the secure posture:</b> while {@code requireSignature} is true,
 *     a blank ARN causes every notification to be refused, because a valid signature
 *     alone proves only that <em>some</em> SNS topic in <em>some</em> AWS account
 *     sent the message - which anybody with an AWS account can arrange - so unpinned
 *     this endpoint would accept a "permanent bounce" for any address a stranger
 *     names and stop that customer's mail. See {@link SnsSignatureVerifier}. Blank is
 *     tolerated only when signature verification has also been switched off, which is
 *     the local-replay posture. A startup warning fires when it is blank.
 * @param region the AWS region whose SNS hosts are trusted. Optional; blank accepts
 *     any syntactically valid {@code sns.<region>.amazonaws.com} host, which is
 *     already a hard SSRF boundary (see {@link SnsEndpointGuard}). Setting it
 *     narrows that to one host and is worth doing.
 * @param complaintsSuppressAllMail whether a spam complaint stops <em>all</em> mail
 *     to the address, or only promotional mail. <b>Defaults to true - all mail -
 *     which is the safer-for-reputation option and the one AWS asks for.</b> This is
 *     a genuine judgement call and {@link EmailSuppressionService} carries the full
 *     argument on both sides; set it false only with a deliberate decision that
 *     delivering receipts to somebody who has reported you as spam is worth the
 *     complaint rate it earns.
 * @param confirmSubscriptions whether a {@code SubscriptionConfirmation} is
 *     confirmed automatically by fetching its {@code SubscribeURL}. Defaults to
 *     true, because the alternative is an operator pasting a URL out of a log into a
 *     browser within the one hour AWS gives them before the token expires. Set false
 *     if your operational policy is that subscriptions are only ever confirmed by
 *     hand in the console.
 * @param connectTimeout TCP connect timeout for the two outbound calls this module
 *     makes - fetching a signing certificate and confirming a subscription.
 * @param readTimeout read timeout for the same two calls. Both are bounded so a
 *     hung AWS endpoint cannot pin a Tomcat worker: this runs on the request thread,
 *     and SNS itself gives up on us after 15 seconds anyway.
 */
@ConfigurationProperties(prefix = "app.email.sns")
public record SesWebhookProperties(
        Boolean requireSignature,
        String topicArn,
        String region,
        Boolean complaintsSuppressAllMail,
        Boolean confirmSubscriptions,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Note the asymmetry between the two booleans, which is deliberate. Absent
     * {@code requireSignature} reads as TRUE - the secure value - so forgetting the
     * key cannot disable the check. Absent {@code confirmSubscriptions} also reads
     * as TRUE, but that one is a convenience rather than a control: the URL it
     * fetches has already passed {@link SnsEndpointGuard} and the message carrying
     * it has already passed signature verification, so auto-confirmation cannot be
     * steered by an attacker. Defaulting it on is what makes the ops runbook a
     * one-liner.
     */
    public SesWebhookProperties {
        requireSignature = requireSignature == null || requireSignature;
        complaintsSuppressAllMail = complaintsSuppressAllMail == null || complaintsSuppressAllMail;
        confirmSubscriptions = confirmSubscriptions == null || confirmSubscriptions;
        connectTimeout = positiveOr(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        readTimeout = positiveOr(readTimeout, DEFAULT_READ_TIMEOUT);
    }

    /** True when a specific topic has been pinned. Blank means every topic is accepted. */
    public boolean hasPinnedTopic() {
        return topicArn != null && !topicArn.isBlank();
    }

    /** True when a specific region has been pinned for the SSRF host check. */
    public boolean hasPinnedRegion() {
        return region != null && !region.isBlank();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
