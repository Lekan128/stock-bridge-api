package com.procurepal_services.stock_bridge_api.email.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.procurepal_services.stock_bridge_api.entity.SesNotificationEvent;
import com.procurepal_services.stock_bridge_api.repository.SesNotificationEventRepository;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Receives SES bounce and complaint notifications relayed by SNS, and decides what
 * they mean.
 *
 * <p>This class parses, authenticates and audits; {@link EmailSuppressionService}
 * makes every state change. The split is a transaction boundary as much as a
 * separation of concerns - see below.
 *
 * <h2>THE RULE THAT MATTERS MOST: a transient bounce changes nothing</h2>
 * If one sentence in this module is worth reading, it is this one. SES reports three
 * bounce types and only one of them means the address is dead:
 * <ul>
 *   <li><strong>Permanent</strong> - the receiving server said definitively that
 *       this address does not accept mail. A mailbox that does not exist, a domain
 *       that no longer resolves. Suppress, and clear {@code is_email_verified}.</li>
 *   <li><strong>Transient</strong> - a temporary condition. <strong>Record it and do
 *       nothing else.</strong> A mailbox over quota, a greylisting server, a
 *       recipient's provider having a bad afternoon, and - the one that catches
 *       people - an out-of-office auto-reply loop all arrive here. Every one of
 *       those resolves by itself, usually within hours. Suppressing on a transient
 *       bounce would mean a customer who went on holiday with a full inbox
 *       permanently stops receiving the receipts for the goods they buy, and
 *       nothing in this system would ever undo it, because nothing ever tells us an
 *       inbox started working again. That is a worse outcome, by a wide margin, than
 *       the handful of retries the bounce itself represents.</li>
 *   <li><strong>Undetermined</strong> - SES could not classify it. Treated exactly
 *       as Transient, and the reasoning is asymmetry of cost rather than optimism:
 *       being wrong towards "transient" means we mail a dead address a few more
 *       times and get told again, which is recoverable and self-correcting, while
 *       being wrong towards "permanent" silently and irreversibly disconnects a
 *       paying customer. When the provider itself does not know, the cheap mistake
 *       is the one to make.</li>
 * </ul>
 *
 * <h2>Two payload dialects, one handler</h2>
 * SES describes the same event two ways depending on how notifications were wired
 * up, and a deployment can easily end up on either. Identity-level notifications
 * (the older {@code aws ses set-identity-notification-topic} route) name the field
 * {@code notificationType}; configuration-set event destinations - which is what
 * {@code EMAIL_CONFIGURATION_SET} and the runbook set up - name it
 * {@code eventType}. The bodies are otherwise the same shape. Reading whichever is
 * present costs one line and removes an entire class of "the webhook receives
 * everything and does nothing" incident.
 *
 * <h2>Why this class is not @Transactional</h2>
 * Its whole job is to record what happened <em>including when the work failed</em>.
 * A transaction spanning the audit write and the state change would roll the audit
 * row back along with the failure, destroying the only evidence that the
 * notification ever arrived - which is precisely the row an operator needs. So the
 * state changes happen inside {@link EmailSuppressionService}'s own short
 * transactions and the audit row is written afterwards, on its own.
 *
 * <p>Effects are applied <em>before</em> the processed row is written, which is the
 * deliberate direction: if the process dies in between, the notification is
 * redelivered and applied again, and every write it makes is idempotent
 * (set-to-a-constant with a guard, upsert on a unique address). The opposite order
 * would leave a message marked done that never took effect, and nothing would ever
 * retry it.
 */
@Service
@Slf4j
public class SesNotificationService {

    /** See MonnifyRestClient for why provider payloads get their own plain mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int NOTE_MAX_LENGTH = 500;

    /**
     * SES event types that are real, expected, and of no interest to a suppression
     * list. Matched case-insensitively and listed explicitly rather than caught by a
     * default branch, so that a type which is genuinely new shows up in the audit
     * table instead of blending in with the noise.
     *
     * <p>These are answered 200 and <strong>not persisted</strong>. That is the one
     * place this module deviates from {@code payment_webhook_events}' "record
     * everything" discipline, and it is a volume argument: a busy configuration set
     * publishing Send/Delivery/Open events emits several rows per email sent, which
     * would grow this table with a multiple of total mail volume in exchange for
     * nothing anybody will ever query. The runbook subscribes only Bounce and
     * Complaint for the same reason; this branch is what makes a misconfigured topic
     * harmless rather than expensive.
     */
    private static final Set<String> IGNORABLE_SES_EVENT_TYPES = Set.of(
            "delivery", "send", "open", "click", "reject", "deliverydelay", "renderingfailure", "subscription");

    private final SesNotificationEventRepository eventRepository;
    private final EmailSuppressionService suppressionService;
    private final SnsSignatureVerifier signatureVerifier;
    private final SnsEndpointGuard endpointGuard;
    private final SesWebhookProperties properties;
    private final HttpClient httpClient;

    public SesNotificationService(
            SesNotificationEventRepository eventRepository,
            EmailSuppressionService suppressionService,
            SnsSignatureVerifier signatureVerifier,
            SnsEndpointGuard endpointGuard,
            SesWebhookProperties properties) {
        this.eventRepository = eventRepository;
        this.suppressionService = suppressionService;
        this.signatureVerifier = signatureVerifier;
        this.endpointGuard = endpointGuard;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                // Never follow a redirect - a validated host must not be able to
                // hand the request to an unvalidated one. Same reasoning as
                // HttpSnsCertificateLoader; see SnsEndpointGuard.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * Warns once, at startup, about the two configurations that leave this endpoint
     * weaker than it should be. Startup is the right moment: it is the one log line
     * an operator reliably reads, and both conditions are deployment mistakes rather
     * than runtime events, so warning per-request would only train people to ignore
     * it.
     */
    @PostConstruct
    void warnAboutInsecureConfiguration() {
        if (!properties.requireSignature()) {
            log.error("app.email.sns.require-signature is FALSE. The SES notification webhook will act on "
                    + "UNSIGNED input, which means anyone who finds POST /api/webhooks/ses/notifications can "
                    + "suppress any address and unverify any customer on this platform. There is no scenario "
                    + "outside local development in which this is acceptable.");
        }
        if (!properties.hasPinnedTopic()) {
            log.warn("app.email.sns.topic-arn is not set, so SES notifications are accepted from ANY SNS topic. "
                    + "A valid AWS signature only proves that some SNS topic sent the message - anyone with an "
                    + "AWS account can point their own topic at this endpoint. Set the ARN in every deployed "
                    + "environment. See stock-bridge-api/DEPLOYMENT.md.");
        }
    }

    /**
     * @param rawBody the request body exactly as received. SNS posts JSON under
     *     {@code Content-Type: text/plain}, and it is parsed here rather than bound
     *     by Spring - which is needed anyway, because signature verification works
     *     over selected envelope fields (see {@link SnsSignatureVerifier}).
     * @return what to tell SNS; {@link SesNotificationOutcome} carries the status
     */
    public SesNotificationOutcome handle(String rawBody) {
        JsonNode envelope = tryParse(rawBody);
        if (envelope == null) {
            record(null, null, null, null, false, false,
                    "Rejected: body was not parseable JSON", rawBody);
            log.warn("An SNS delivery carried a body that was not parseable JSON");
            return SesNotificationOutcome.MALFORMED;
        }

        String messageId = text(envelope, "MessageId");
        String messageType = text(envelope, "Type");

        boolean signatureValid = signatureVerifier.isValid(envelope);

        if (!signatureValid && signatureVerifier.isSignatureRequired()) {
            record(messageId, messageType, null, null, false, false,
                    "Rejected: SNS signature did not verify", rawBody);
            log.warn("Rejected an SNS delivery whose signature did not verify (Type={}, MessageId={}). "
                    + "Nothing was changed.", messageType, messageId);
            return SesNotificationOutcome.REJECTED_SIGNATURE;
        }
        if (!signatureValid) {
            // Only reachable with require-signature switched off. Recorded as
            // signature_valid = false regardless, so a deployment running this way
            // is obvious from the data and not only from the config.
            log.error("Processing an UNSIGNED SNS notification because app.email.sns.require-signature is false "
                    + "(MessageId={}). This must never happen outside local development.", messageId);
        }

        if (!signatureVerifier.isFromExpectedTopic(envelope)) {
            record(messageId, messageType, null, null, signatureValid, false,
                    "Rejected: TopicArn is not the configured app.email.sns.topic-arn", rawBody);
            return SesNotificationOutcome.REJECTED_TOPIC;
        }

        // Idempotency. SNS delivers at least once and retries anything not answered
        // 2xx, so this is the ordinary path rather than an edge case. The duplicate
        // is still recorded - marked unprocessed, which is what keeps it out of the
        // partial unique index - because losing the evidence of a retry hides
        // exactly the provider misbehaviour you would want to see.
        if (messageId != null && eventRepository.existsByMessageIdAndProcessedTrue(messageId)) {
            record(messageId, messageType, null, null, signatureValid, false,
                    "Ignored: this MessageId has already been processed", rawBody);
            log.info("Ignoring a redelivered SNS notification (MessageId={}); it was already applied.", messageId);
            return SesNotificationOutcome.ACCEPTED;
        }

        try {
            return dispatch(envelope, messageId, messageType, signatureValid, rawBody);
        } catch (SnsEndpointRefusedException e) {
            record(messageId, messageType, null, null, signatureValid, false,
                    "Refused: " + e.getMessage(), rawBody);
            return SesNotificationOutcome.REFUSED_ENDPOINT;
        } catch (DataIntegrityViolationException e) {
            // The partial unique index fired: another instance processed this exact
            // MessageId between our duplicate check and our write. The work was
            // idempotent, so the end state is correct either way - this is a race we
            // designed for, not a failure.
            log.info("A concurrent instance had already processed SNS MessageId={}; treating as a duplicate.",
                    messageId);
            return SesNotificationOutcome.ACCEPTED;
        } catch (Exception e) {
            // Believed it, could not apply it. 503 so SNS retries, which is the one
            // situation where retrying is genuinely the remedy. Recorded as
            // unprocessed on a best-effort basis - if the database is what failed,
            // this write fails too, and the log line is all there is.
            log.error("Could not apply an SNS notification (MessageId={}): {}", messageId, e.getMessage(), e);
            try {
                record(messageId, messageType, null, null, signatureValid, false,
                        "Failed: " + e.getMessage(), rawBody);
            } catch (Exception ignored) {
                log.error("Could not even record the failed SNS notification; the log above is the only trace.");
            }
            return SesNotificationOutcome.TEMPORARY_FAILURE;
        }
    }

    // ------------------------------------------------------------------------

    private SesNotificationOutcome dispatch(
            JsonNode envelope, String messageId, String messageType, boolean signatureValid, String rawBody) {

        SnsMessageType type = SnsMessageType.from(messageType);
        if (type == null) {
            // Recorded and answered 200. Retrying would not make a type we do not
            // understand become one we do, and SNS would spend hours discovering
            // that.
            record(messageId, messageType, null, null, signatureValid, true,
                    "Ignored: unrecognised SNS message Type", rawBody);
            log.warn("Received an SNS message of unrecognised Type '{}'", messageType);
            return SesNotificationOutcome.ACCEPTED;
        }

        return switch (type) {
            case SUBSCRIPTION_CONFIRMATION ->
                    confirmSubscription(envelope, messageId, messageType, signatureValid, rawBody);
            case UNSUBSCRIBE_CONFIRMATION ->
                    noteUnsubscribe(messageId, messageType, signatureValid, rawBody);
            case NOTIFICATION ->
                    handleNotification(envelope, messageId, messageType, signatureValid, rawBody);
        };
    }

    /**
     * Confirms the HTTP subscription by fetching {@code SubscribeURL}.
     *
     * <p>The URL comes out of a request body, so it goes through
     * {@link SnsEndpointGuard} before any socket is opened - an unvalidated fetch
     * here would be textbook SSRF, with the instance metadata endpoint one crafted
     * message away. The guard throws rather than returning a flag, and that
     * exception is deliberately not caught in this method: it propagates to
     * {@link #handle}, which records the refusal and answers 400.
     *
     * <p>The confirmation is fetched inline, before responding. AWS gives one hour
     * before the token expires, so there is no urgency in the milliseconds - but
     * doing it here means a subscription that reports "Confirmed" in the console has
     * demonstrably been confirmed by this process, rather than by something that may
     * or may not have run later.
     */
    private SesNotificationOutcome confirmSubscription(
            JsonNode envelope, String messageId, String messageType, boolean signatureValid, String rawBody) {

        String subscribeUrl = text(envelope, "SubscribeURL");
        if (!properties.confirmSubscriptions()) {
            record(messageId, messageType, null, null, signatureValid, true,
                    "Ignored: automatic subscription confirmation is disabled", rawBody);
            log.warn("An SNS SubscriptionConfirmation arrived but app.email.sns.confirm-subscriptions is false. "
                    + "Confirm it by hand within one hour or the subscription expires. SubscribeURL: {}",
                    subscribeUrl);
            return SesNotificationOutcome.ACCEPTED;
        }

        URI uri = endpointGuard.requireSubscribeUrl(subscribeUrl);
        String note;
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(uri).GET().timeout(properties.readTimeout()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                note = "Confirmed the SNS subscription";
                log.info("Confirmed the SES notification topic subscription (TopicArn={})",
                        text(envelope, "TopicArn"));
            } else {
                note = "Subscription confirmation returned HTTP " + response.statusCode();
                log.error("Confirming the SNS subscription returned HTTP {} - bounce notifications will not "
                        + "arrive until this subscription is confirmed.", response.statusCode());
            }
        } catch (Exception e) {
            // Not fatal to the request and not worth a retry storm: the token is
            // valid for an hour and an operator can confirm from the console. Left
            // as an accepted message with the failure in the note.
            note = "Subscription confirmation failed: " + e.getMessage();
            log.error("Could not confirm the SNS subscription: {}", e.getMessage());
        }
        record(messageId, messageType, null, null, signatureValid, true, note, rawBody);
        return SesNotificationOutcome.ACCEPTED;
    }

    /**
     * Nothing to do, and that is exactly why it is logged at ERROR.
     *
     * <p>This message means the subscription has been removed: from now on SES
     * bounces and complaints stop arriving, the suppression list stops growing, and
     * every other signal in this application looks completely healthy while the
     * bounce rate climbs at AWS towards a suspension. It is the one event in this
     * module with no symptom of its own, so the log line is the symptom.
     */
    private SesNotificationOutcome noteUnsubscribe(
            String messageId, String messageType, boolean signatureValid, String rawBody) {
        log.error("The SES notification topic subscription for this endpoint has been REMOVED. Bounce and "
                + "complaint notifications will no longer reach this application, and nothing else will show "
                + "it. Re-subscribe the endpoint - see stock-bridge-api/DEPLOYMENT.md.");
        record(messageId, messageType, null, null, signatureValid, true,
                "Recorded: the topic subscription was removed", rawBody);
        return SesNotificationOutcome.ACCEPTED;
    }

    private SesNotificationOutcome handleNotification(
            JsonNode envelope, String messageId, String messageType, boolean signatureValid, String rawBody) {

        // The SES message is JSON nested as a STRING inside the SNS envelope's
        // Message field - not as an object. Parsing it separately is required, not a
        // stylistic choice.
        JsonNode message = tryParse(text(envelope, "Message"));
        if (message == null) {
            record(messageId, messageType, null, null, signatureValid, true,
                    "Ignored: the SNS Message field was not parseable SES JSON", rawBody);
            log.warn("An SNS Notification carried a Message that was not parseable JSON (MessageId={})", messageId);
            return SesNotificationOutcome.ACCEPTED;
        }

        // Two dialects, one handler - see the class doc.
        String notificationType = firstNonBlank(text(message, "notificationType"), text(message, "eventType"));
        String normalizedType = notificationType == null
                ? "" : notificationType.replace(" ", "").toLowerCase(Locale.ROOT);

        if ("bounce".equals(normalizedType)) {
            return handleBounce(message, messageId, messageType, signatureValid, rawBody);
        }
        if ("complaint".equals(normalizedType)) {
            return handleComplaint(message, messageId, messageType, signatureValid, rawBody);
        }
        if (IGNORABLE_SES_EVENT_TYPES.contains(normalizedType)) {
            // Cheap by design: answered 200, no row written. See the constant.
            log.debug("Ignoring an SES '{}' event - this endpoint only acts on Bounce and Complaint.",
                    notificationType);
            return SesNotificationOutcome.ACCEPTED;
        }

        record(messageId, messageType, notificationType, null, signatureValid, true,
                "Ignored: SES notification type is not one this endpoint acts on", rawBody);
        log.warn("Received an SES notification of unrecognised type '{}'", notificationType);
        return SesNotificationOutcome.ACCEPTED;
    }

    /**
     * A bounce, for every recipient it names.
     *
     * <p>{@code bouncedRecipients} is a list because one SES send can address several
     * people and any subset of them can bounce. Each entry is handled independently
     * and carries its own {@code diagnosticCode}: they can differ, one address can
     * be permanently dead while another was merely over quota in the same message,
     * and collapsing them to the first would suppress the wrong inbox. The bounce
     * TYPE is shared across the list - SES classifies the bounce, not the recipient -
     * which is why the permanent/transient decision is made once, above the loop.
     */
    private SesNotificationOutcome handleBounce(
            JsonNode message, String messageId, String messageType, boolean signatureValid, String rawBody) {

        JsonNode bounce = message.get("bounce");
        String bounceType = bounce == null ? null : text(bounce, "bounceType");
        String bounceSubType = bounce == null ? null : text(bounce, "bounceSubType");
        String feedbackId = bounce == null ? null : text(bounce, "feedbackId");
        String sourceMessageId = firstNonBlank(messageId, feedbackId);

        // THE decision. Anything that is not exactly "Permanent" - Transient,
        // Undetermined, a value SES has not documented yet, or a missing field -
        // changes nothing. Written as an allow-list of one for that reason: a
        // deny-list would let an unrecognised value fall through to suppression,
        // and the whole point is that only an unambiguous permanent failure may.
        boolean permanent = "Permanent".equalsIgnoreCase(bounceType);

        JsonNode recipients = bounce == null ? null : bounce.get("bouncedRecipients");
        int addressCount = 0;
        int usersDemoted = 0;
        if (recipients != null && recipients.isArray()) {
            for (JsonNode recipient : recipients) {
                String address = text(recipient, "emailAddress");
                if (address == null || address.isBlank()) {
                    continue;
                }
                addressCount++;
                if (permanent) {
                    usersDemoted += suppressionService.suppressPermanentBounce(
                            address, sourceMessageId, text(recipient, "diagnosticCode"));
                }
            }
        }

        String note;
        if (permanent) {
            note = "Permanent bounce: suppressed " + addressCount + " address(es), unverified "
                    + usersDemoted + " user row(s)";
            log.info("Applied a permanent bounce to {} address(es) (subType={})", addressCount, bounceSubType);
        } else {
            // Recorded, and nothing else. See the class doc - this is the single
            // most important correctness rule in the module.
            note = "Transient/undetermined bounce (" + bounceType + "): recorded, no addresses suppressed";
            log.info("Recorded a non-permanent bounce (type={}, subType={}) for {} address(es). Nothing was "
                    + "suppressed and no user was unverified - this is deliberate.",
                    bounceType, bounceSubType, addressCount);
        }
        record(messageId, messageType, "Bounce", bounceType, signatureValid, true, note, rawBody);
        return SesNotificationOutcome.ACCEPTED;
    }

    /**
     * A complaint, for every recipient it names.
     *
     * <p>The policy - promotional only, or all mail - is
     * {@link EmailSuppressionService}'s to state and this method's only job is to
     * find the addresses and the feedback type. The one thing filtered here is
     * {@code not-spam}, which is a feedback report meaning a reader rescued our mail
     * from their spam folder; acting on it as a complaint would silence precisely the
     * customers who signalled that the mail was wanted.
     */
    private SesNotificationOutcome handleComplaint(
            JsonNode message, String messageId, String messageType, boolean signatureValid, String rawBody) {

        JsonNode complaint = message.get("complaint");
        String feedbackType = complaint == null ? null : text(complaint, "complaintFeedbackType");
        String feedbackId = complaint == null ? null : text(complaint, "feedbackId");
        String sourceMessageId = firstNonBlank(messageId, feedbackId);

        if (feedbackType != null && "not-spam".equalsIgnoreCase(feedbackType.trim())) {
            record(messageId, messageType, "Complaint", feedbackType, signatureValid, true,
                    "Ignored: 'not-spam' feedback is an endorsement, not a complaint", rawBody);
            log.info("Ignoring a 'not-spam' SES feedback report - the reader moved our mail OUT of spam.");
            return SesNotificationOutcome.ACCEPTED;
        }

        JsonNode recipients = complaint == null ? null : complaint.get("complainedRecipients");
        int addressCount = 0;
        int usersOptedOut = 0;
        if (recipients != null && recipients.isArray()) {
            for (JsonNode recipient : recipients) {
                String address = text(recipient, "emailAddress");
                if (address == null || address.isBlank()) {
                    continue;
                }
                addressCount++;
                usersOptedOut += suppressionService.recordComplaint(address, sourceMessageId, feedbackType);
            }
        }

        String note = "Complaint (" + feedbackType + "): "
                + (properties.complaintsSuppressAllMail() ? "suppressed all mail to " : "opted out of promotional for ")
                + addressCount + " address(es), " + usersOptedOut + " user row(s) opted out";
        record(messageId, messageType, "Complaint", feedbackType, signatureValid, true, note, rawBody);
        log.info("Applied a complaint to {} address(es) (feedbackType={})", addressCount, feedbackType);
        return SesNotificationOutcome.ACCEPTED;
    }

    // ------------------------------------------------------------------------

    /**
     * Writes the audit row. Every exit path from {@link #handle} goes through here
     * exactly once, except the deliberately-cheap ignore for high-volume SES event
     * types - so "did this arrive" is answerable for anything that mattered.
     */
    private void record(
            String messageId,
            String messageType,
            String notificationType,
            String subType,
            boolean signatureValid,
            boolean processed,
            String note,
            String rawBody) {
        eventRepository.save(SesNotificationEvent.builder()
                .messageId(truncate(messageId, 200))
                .messageType(truncate(messageType, 60))
                .notificationType(truncate(notificationType, 60))
                .subType(truncate(subType, 60))
                .signatureValid(signatureValid)
                .processed(processed)
                .processingNote(truncate(note, NOTE_MAX_LENGTH))
                .payload(storablePayload(rawBody))
                .build());
    }

    private static JsonNode tryParse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code ses_notification_events.payload} is a non-null JSONB column, so a
     * malformed body cannot be stored verbatim - and dropping it would destroy the
     * one artefact needed to work out what a broken sender actually sent. Wrapping
     * keeps the bytes and satisfies the column, exactly as
     * {@code MonnifyWebhookService} does.
     */
    private static String storablePayload(String rawBody) {
        if (tryParse(rawBody) != null) {
            return rawBody;
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("_unparseable", true);
        wrapper.put("_raw", rawBody == null ? "" : rawBody);
        return wrapper.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
