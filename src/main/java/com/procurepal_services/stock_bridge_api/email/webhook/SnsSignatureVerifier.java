package com.procurepal_services.stock_bridge_api.email.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The webhook's only authentication: an RSA signature over a canonical rendering of
 * the SNS envelope, verified with a public key fetched from AWS.
 *
 * <h2>Why this matters more than the Monnify equivalent it is modelled on</h2>
 * {@code MonnifySignatureVerifier} guards an endpoint where a forged message can, at
 * worst, make the server ask Monnify a question - the payment is applied from a
 * status this server fetches itself, so the callback is a trigger and never
 * evidence. There is no such backstop here. SES exposes no API that would let this
 * code re-ask "did that address really bounce", so the notification body <em>is</em>
 * the evidence and it is acted on directly: an accepted permanent bounce suppresses
 * an address and clears {@code is_email_verified} on every account holding it, in
 * every tenant. Unsigned, this endpoint is a public API for silencing any
 * customer's order receipts, one POST at a time, for anybody who guesses the path.
 * Everything in this class exists because of that sentence.
 *
 * <h2>Why the canonical string is rebuilt rather than the raw body hashed</h2>
 * This is the structural difference from the Monnify verifier and the thing most
 * likely to be "simplified" later, so it is worth stating plainly: SNS does
 * <em>not</em> sign the bytes it sent. It signs a canonical document assembled from
 * a specific, ordered subset of the envelope's fields - {@code Signature},
 * {@code SigningCertURL} and {@code SignatureVersion} are themselves excluded, and
 * so is any field AWS adds later. Hashing the raw body, which is exactly the right
 * answer for Monnify, would fail every message here. Hence the field lists below,
 * which are copied from AWS's specification rather than inferred from a sample.
 *
 * <p>The format is {@code name\nvalue\n} per field, in the documented order, UTF-8.
 * {@code Subject} appears only when present - and "present" means the key exists,
 * not that it is non-empty, which is a distinction that silently breaks
 * verification for notifications with an empty subject if it is got wrong.
 *
 * <h2>The limit of what a valid signature proves - read this before trusting it</h2>
 * A valid signature proves the message was produced by <em>an</em> SNS topic, in
 * <em>some</em> AWS account. It does not prove it was produced by <em>ours</em>.
 * Anybody can create an SNS topic, publish a hand-written bounce notification to an
 * HTTP subscription pointed at this endpoint, and have it signed by the same AWS
 * infrastructure and the same certificate chain this class validates. Signature
 * verification alone is therefore necessary and not sufficient, and a webhook that
 * stops there has a hole in it that looks exactly like security.
 *
 * <p>{@link #isFromExpectedTopic} closes it by pinning {@code TopicArn} to
 * {@code app.email.sns.topic-arn}. That check is separate from the signature check
 * on purpose: they fail for different reasons and an operator needs to tell "someone
 * is forging messages" from "someone has pointed a second topic at us", which the
 * audit rows do only if the notes differ.
 *
 * <h2>Never throws</h2>
 * Every failure - a refused certificate URL, an unreachable AWS, an unknown
 * signature version, a corrupt Base64 blob - resolves to {@code false}. "Cannot
 * verify" must never read as "valid", which is the same rule
 * {@code MonnifySignatureVerifier} states for a missing secret key; but it must also
 * never read as a 500, because an exception escaping here would lose the audit row
 * that records the attempt. The one thing this class emits is a boolean.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SnsSignatureVerifier {

    /**
     * AWS's documented field order for a {@code Notification}. {@code Subject} is
     * included only when the key is present. Order is part of the specification, not
     * a convention - re-sorting this array breaks every signature.
     */
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");

    /**
     * The order for {@code SubscriptionConfirmation} and
     * {@code UnsubscribeConfirmation}. Different from the above in more than the
     * extra fields: these two carry {@code SubscribeURL} and {@code Token} and never
     * carry {@code Subject}.
     */
    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final SnsCertificateLoader certificateLoader;
    private final SesWebhookProperties properties;

    /**
     * @param envelope the parsed SNS envelope. Parsed rather than raw because,
     *     unlike Monnify, the signature is over selected fields and not over the
     *     bytes - see the class doc.
     * @return true only if a signature was present and verified against the
     *     certificate AWS published
     */
    public boolean isValid(JsonNode envelope) {
        if (envelope == null) {
            return false;
        }
        try {
            String signatureVersion = text(envelope, "SignatureVersion");
            String algorithm = algorithmFor(signatureVersion);
            if (algorithm == null) {
                // Refused rather than defaulted. A version this code does not know
                // is a message it cannot check, and an unknown version is also
                // exactly what a downgrade attempt would look like.
                log.warn("Refusing an SNS message with an unrecognised SignatureVersion '{}'", signatureVersion);
                return false;
            }

            String encodedSignature = text(envelope, "Signature");
            if (encodedSignature == null || encodedSignature.isBlank()) {
                log.warn("Refusing an SNS message that carried no Signature at all");
                return false;
            }

            String canonicalString = canonicalStringFor(envelope);
            if (canonicalString == null) {
                return false;
            }

            // Fetches (and caches) the certificate. The URL is allow-listed inside
            // the loader before any socket is opened - see SnsEndpointGuard.
            PublicKey publicKey = certificateLoader.publicKeyFor(text(envelope, "SigningCertURL"));

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(canonicalString.getBytes(StandardCharsets.UTF_8));
            boolean valid = verifier.verify(Base64.getDecoder().decode(encodedSignature));
            if (!valid) {
                log.warn("An SNS message failed signature verification (Type={}, MessageId={})",
                        text(envelope, "Type"), text(envelope, "MessageId"));
            }
            return valid;
        } catch (SnsEndpointRefusedException e) {
            // Already logged in full by the guard, including the refused URL.
            return false;
        } catch (Exception e) {
            // No constant-time comparison is needed anywhere in this method, unlike
            // the Monnify HMAC path: RSA verification takes a public key and a
            // signature and returns a boolean, and there is no secret here whose
            // bytes a timing channel could leak. The public key is public.
            log.warn("Could not verify an SNS message signature; treating it as invalid: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Is this message from the topic we expect?
     *
     * <p>A separate question from {@link #isValid}, and the class doc explains why it
     * has to be asked at all: a signature proves AWS sent the message, not that our
     * topic did. Anyone with an AWS account can publish a validly-signed bounce
     * notification to this URL.
     *
     * <p>Returns true when no topic is pinned. That is a deliberate, documented
     * weakness rather than an oversight: requiring the ARN would mean an operator
     * who has wired up SES and SNS correctly but not yet set one more environment
     * variable sees every notification refused, with a symptom (a rising bounce rate
     * that never suppresses anything) that takes a long time to notice. The
     * mitigation is a loud startup warning from {@code SesNotificationService} and
     * an emphatic line in the runbook, not a default that breaks the feature.
     */
    public boolean isFromExpectedTopic(JsonNode envelope) {
        if (!properties.hasPinnedTopic()) {
            // An unpinned topic is refused whenever this endpoint is otherwise in its
            // secure posture, because in that posture the signature is the ONLY other
            // control and it does not prove what it looks like it proves: a valid
            // signature says some SNS topic in some AWS account sent this, and anybody
            // with an AWS account can create a topic and point it here. Unpinned, a
            // correctly-signed forgery from a stranger's topic is indistinguishable
            // from a real bounce, and the endpoint's whole effect is to stop mail to
            // an address of the sender's choosing.
            //
            // Refusing costs a misconfigured deployment its bounce processing, which
            // is bad but bounded and loud - a 403 per delivery, an audit row each
            // time, and a warning at startup. Accepting costs it every customer's
            // email on the say-so of anyone who finds the URL. The asymmetry decides
            // it.
            //
            // Tied to require-signature rather than enforced unconditionally so the
            // two form ONE posture: a developer who has already switched signature
            // verification off to replay a captured payload locally is not then asked
            // for a topic ARN they do not have.
            if (properties.requireSignature()) {
                log.warn("Refusing an SNS notification because app.email.sns.topic-arn is not set. A valid "
                        + "signature only proves that some AWS account sent this, so without a pinned topic "
                        + "this endpoint would accept bounce reports from anyone. Set the topic ARN.");
                return false;
            }
            return true;
        }
        String topicArn = envelope == null ? null : text(envelope, "TopicArn");
        boolean matches = properties.topicArn().trim().equals(topicArn);
        if (!matches) {
            log.warn("Refusing a correctly-signed SNS message from an unexpected topic. Expected '{}', got '{}'. "
                    + "A valid signature only proves AWS sent this - anybody can point their own topic here.",
                    properties.topicArn().trim(), topicArn);
        }
        return matches;
    }

    /** See {@link SesWebhookProperties#requireSignature()} - defaults to true and must stay true. */
    public boolean isSignatureRequired() {
        return properties.requireSignature();
    }

    /**
     * Builds the exact document AWS signed.
     *
     * <p>Package-private rather than private so the test can assert on the string
     * itself. That is worth the widened visibility: the field order and the trailing
     * newline are specification details with no local justification, a mistake in
     * either produces "signature invalid" and nothing more, and debugging that from
     * a boolean is miserable.
     *
     * @return null when the message type is one whose canonical form is undefined
     */
    String canonicalStringFor(JsonNode envelope) {
        String type = text(envelope, "Type");
        List<String> fields;
        if (SnsMessageType.NOTIFICATION.matches(type)) {
            fields = NOTIFICATION_FIELDS;
        } else if (SnsMessageType.SUBSCRIPTION_CONFIRMATION.matches(type)
                || SnsMessageType.UNSUBSCRIBE_CONFIRMATION.matches(type)) {
            fields = CONFIRMATION_FIELDS;
        } else {
            log.warn("Cannot build a canonical string for an SNS message of unknown Type '{}'", type);
            return null;
        }

        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            JsonNode value = envelope.get(field);
            // Presence, not emptiness. Subject is the only optional field, and a
            // notification with an empty subject DOES include the key - excluding it
            // on blankness would fail exactly those messages and only those.
            if (value == null || value.isNull()) {
                continue;
            }
            canonical.append(field).append('\n').append(value.asText()).append('\n');
        }
        return canonical.toString();
    }

    /**
     * Version 1 is SHA1, version 2 is SHA256. SHA-1 is collision-broken and would
     * never be chosen today, but AWS still signs version 1 messages and refusing
     * them would refuse real bounce notifications. The exposure is narrow enough to
     * accept: forging a message here needs a preimage against a key held by AWS, not
     * a collision between two documents an attacker authored, and the version 2
     * branch is what actually runs for topics created in the last several years.
     */
    private static String algorithmFor(String signatureVersion) {
        if ("1".equals(signatureVersion)) {
            return "SHA1withRSA";
        }
        if ("2".equals(signatureVersion)) {
            return "SHA256withRSA";
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
