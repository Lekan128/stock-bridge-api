package com.procurepal_services.stock_bridge_api.email.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Builds SNS envelopes and signs them, for tests on both sides of the wire.
 *
 * <h2>Why the canonical string is rebuilt here instead of reused</h2>
 * {@link SnsSignatureVerifier} exposes its own {@code canonicalStringFor}, and
 * calling it from here would be the obvious shortcut. It is deliberately not taken,
 * for the reason {@code MonnifySignatureVerifierTest} states about its own hash: a
 * test that signs with the implementation under test agrees with itself no matter
 * what either of them does. The field lists below are transcribed from AWS's
 * published specification, so if somebody re-orders the production arrays or drops
 * {@code Subject}, these fixtures keep signing what AWS would sign and the
 * verification tests fail - which is the entire value of having them.
 *
 * <h2>The key pair</h2>
 * Generated once per JVM. RSA-2048 keygen is not free, and every test here needs the
 * same pair anyway, since the point is to stand in for the certificate AWS publishes
 * at {@code SigningCertURL}.
 */
final class SnsTestMessages {

    static final KeyPair KEY_PAIR = generateKeyPair();

    static final String CERT_URL =
            "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-a86cb10b4e1f29c941702d737128f7b6.pem";
    static final String TOPIC_ARN = "arn:aws:sns:eu-west-1:123456789012:procurepal-ses-notifications";
    static final String SUBSCRIBE_URL =
            "https://sns.eu-west-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=" + TOPIC_ARN + "&Token=tok";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** AWS's documented order for a Notification. Subject only when present. */
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");

    /** AWS's documented order for SubscriptionConfirmation and UnsubscribeConfirmation. */
    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private SnsTestMessages() {}

    /** A Notification carrying an SES payload, signed with SignatureVersion 2 (SHA256). */
    static String notification(String messageId, String sesMessageJson) {
        return notification(messageId, sesMessageJson, TOPIC_ARN, "2");
    }

    static String notification(String messageId, String sesMessageJson, String topicArn, String signatureVersion) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("Type", "Notification");
        envelope.put("MessageId", messageId);
        envelope.put("TopicArn", topicArn);
        // The SES payload is JSON nested as a STRING, which is how SNS actually
        // sends it and the reason the service parses Message separately.
        envelope.put("Message", sesMessageJson);
        envelope.put("Timestamp", "2026-08-20T09:15:00.000Z");
        return sign(envelope, NOTIFICATION_FIELDS, signatureVersion);
    }

    static String subscriptionConfirmation(String messageId, String subscribeUrl) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("Type", "SubscriptionConfirmation");
        envelope.put("MessageId", messageId);
        envelope.put("Token", "tok");
        envelope.put("TopicArn", TOPIC_ARN);
        envelope.put("Message", "You have chosen to subscribe to the topic " + TOPIC_ARN);
        envelope.put("SubscribeURL", subscribeUrl);
        envelope.put("Timestamp", "2026-08-20T09:15:00.000Z");
        return sign(envelope, CONFIRMATION_FIELDS, "2");
    }

    static String unsubscribeConfirmation(String messageId) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("Type", "UnsubscribeConfirmation");
        envelope.put("MessageId", messageId);
        envelope.put("Token", "tok");
        envelope.put("TopicArn", TOPIC_ARN);
        envelope.put("Message", "You have chosen to deactivate subscription");
        envelope.put("SubscribeURL", SUBSCRIBE_URL);
        envelope.put("Timestamp", "2026-08-20T09:15:00.000Z");
        return sign(envelope, CONFIRMATION_FIELDS, "2");
    }

    // ------------------------------------------------------------------------
    // SES payloads, in the shapes AWS documents.
    // ------------------------------------------------------------------------

    static String bounce(String bounceType, String... addresses) {
        StringBuilder recipients = new StringBuilder();
        for (String address : addresses) {
            if (!recipients.isEmpty()) {
                recipients.append(",");
            }
            recipients.append("{\"emailAddress\":\"").append(address)
                    .append("\",\"diagnosticCode\":\"smtp; 550 5.1.1 user unknown\"}");
        }
        return "{\"notificationType\":\"Bounce\",\"bounce\":{\"bounceType\":\"" + bounceType
                + "\",\"bounceSubType\":\"General\",\"feedbackId\":\"fb-1\",\"bouncedRecipients\":["
                + recipients + "]},\"mail\":{\"messageId\":\"ses-1\"}}";
    }

    static String complaint(String feedbackType, String... addresses) {
        StringBuilder recipients = new StringBuilder();
        for (String address : addresses) {
            if (!recipients.isEmpty()) {
                recipients.append(",");
            }
            recipients.append("{\"emailAddress\":\"").append(address).append("\"}");
        }
        String feedback = feedbackType == null ? "" : "\"complaintFeedbackType\":\"" + feedbackType + "\",";
        return "{\"notificationType\":\"Complaint\",\"complaint\":{" + feedback
                + "\"feedbackId\":\"fb-2\",\"complainedRecipients\":[" + recipients
                + "]},\"mail\":{\"messageId\":\"ses-2\"}}";
    }

    /**
     * The configuration-set event-destination dialect, which names the field
     * {@code eventType} instead of {@code notificationType}. Both reach a real
     * deployment depending on how notifications were wired up.
     */
    static String bounceInEventPublishingDialect(String bounceType, String address) {
        return "{\"eventType\":\"Bounce\",\"bounce\":{\"bounceType\":\"" + bounceType
                + "\",\"bounceSubType\":\"General\",\"bouncedRecipients\":[{\"emailAddress\":\"" + address
                + "\"}]},\"mail\":{\"messageId\":\"ses-3\"}}";
    }

    static String delivery(String address) {
        return "{\"notificationType\":\"Delivery\",\"delivery\":{\"recipients\":[\"" + address
                + "\"]},\"mail\":{\"messageId\":\"ses-4\"}}";
    }

    // ------------------------------------------------------------------------

    /** Replaces the Signature with rubbish, leaving everything else intact. */
    static String withBrokenSignature(String signedEnvelope) {
        try {
            ObjectNode envelope = (ObjectNode) MAPPER.readTree(signedEnvelope);
            envelope.put("Signature", Base64.getEncoder().encodeToString(new byte[256]));
            return envelope.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalString(ObjectNode envelope, List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            if (envelope.has(field) && !envelope.get(field).isNull()) {
                canonical.append(field).append('\n').append(envelope.get(field).asText()).append('\n');
            }
        }
        return canonical.toString();
    }

    private static String sign(ObjectNode envelope, List<String> fields, String signatureVersion) {
        try {
            String algorithm = "1".equals(signatureVersion) ? "SHA1withRSA" : "SHA256withRSA";
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign((PrivateKey) KEY_PAIR.getPrivate());
            signer.update(canonicalString(envelope, fields).getBytes(StandardCharsets.UTF_8));
            envelope.put("SignatureVersion", signatureVersion);
            envelope.put("Signature", Base64.getEncoder().encodeToString(signer.sign()));
            envelope.put("SigningCertURL", CERT_URL);
            return envelope.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
