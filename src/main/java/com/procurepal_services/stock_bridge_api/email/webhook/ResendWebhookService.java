package com.procurepal_services.stock_bridge_api.email.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.procurepal_services.stock_bridge_api.email.ResendProperties;
import com.procurepal_services.stock_bridge_api.entity.SesNotificationEvent;
import com.procurepal_services.stock_bridge_api.repository.SesNotificationEventRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Resend's bounce and complaint webhooks - the Resend counterpart of
 * {@link SesNotificationService}, feeding the same {@link EmailSuppressionService}.
 * A permanent bounce suppresses the address and unverifies every account holding it;
 * a complaint opts it out of marketing and, by default, all mail
 * ({@code app.email.sns.complaints-suppress-all-mail}, shared with SES because it is
 * a policy about complainants, not about a provider). A temporary bounce changes
 * nothing, for the reason SesNotificationService gives: a full mailbox must never
 * cost a customer their order receipts.
 *
 * <h2>Audited in ses_notification_events</h2>
 * The table predates the second provider and its name says SES, but its shape -
 * message id, type, sub-type, signature verdict, processed flag, note, raw payload -
 * is provider-neutral, and its partial unique index on processed message ids is
 * exactly the dedupe this needs. Resend rows are told apart by
 * {@code message_type = 'resend'} and a {@code msg_...} Svix id that cannot collide
 * with an SNS UUID. A second, identically-shaped table would split "why is this
 * address suppressed" across two places for no gain.
 *
 * <h2>Status codes</h2>
 * Svix retries any non-2xx with backoff for about a day. So anything understood and
 * finished with - including duplicates and event types this ignores - is 200; a bad
 * signature is 401 (never 2xx: if our secret is what is wrong, a 2xx would have every
 * real bounce discarded silently); a body that is not an event is 400; a failure to
 * apply is 503 so it comes back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResendWebhookService {

    static final String MESSAGE_TYPE = "resend";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int NOTE_MAX_LENGTH = 500;

    private final ResendSignatureVerifier signatureVerifier;
    private final EmailSuppressionService suppressionService;
    private final SesNotificationEventRepository eventRepository;
    private final ResendProperties properties;

    @PostConstruct
    void warnIfUnconfigured() {
        if (!properties.hasWebhookSecret()) {
            log.warn("app.email.resend.webhook-secret is not set, so every Resend webhook delivery will be refused "
                    + "and bounces/complaints will not reach the suppression list. Set it from the webhook's "
                    + "signing secret in the Resend dashboard.");
        }
    }

    public HttpStatus handle(String svixId, String svixTimestamp, String svixSignature, byte[] body) {
        String rawBody = body == null ? null : new String(body, StandardCharsets.UTF_8);

        if (!signatureVerifier.isValid(svixId, svixTimestamp, svixSignature, body)) {
            record(svixId, null, null, false, false, "Rejected: Resend/Svix signature did not verify", rawBody);
            log.warn("Rejected a Resend webhook whose signature did not verify (svix-id={}). Nothing was changed.",
                    svixId);
            return HttpStatus.UNAUTHORIZED;
        }

        JsonNode event = tryParse(rawBody);
        if (event == null) {
            record(svixId, null, null, true, false, "Rejected: body was not a JSON object", rawBody);
            return HttpStatus.BAD_REQUEST;
        }

        if (eventRepository.existsByMessageIdAndProcessedTrue(svixId)) {
            log.info("Ignoring a redelivered Resend webhook (svix-id={}); it was already applied.", svixId);
            return HttpStatus.OK;
        }

        String type = text(event, "type");
        JsonNode data = event.get("data");
        try {
            if ("email.bounced".equals(type)) {
                handleBounce(svixId, type, data, rawBody);
            } else if ("email.complained".equals(type)) {
                handleComplaint(svixId, type, data, rawBody);
            } else if ("email.failed".equals(type)) {
                handleFailed(svixId, type, data, rawBody);
            } else {
                // Only the three events above should be subscribed in the dashboard;
                // anything else is acknowledged so Svix stops retrying it.
                log.debug("Ignoring a Resend '{}' event - this endpoint only acts on bounces and complaints.", type);
            }
            return HttpStatus.OK;
        } catch (DataIntegrityViolationException e) {
            log.info("A concurrent instance had already processed Resend svix-id={}; treating as a duplicate.",
                    svixId);
            return HttpStatus.OK;
        } catch (Exception e) {
            log.error("Could not apply a Resend webhook (svix-id={}): {}", svixId, e.getMessage(), e);
            try {
                record(svixId, type, null, true, false, "Failed: " + e.getMessage(), rawBody);
            } catch (Exception ignored) {
                log.error("Could not even record the failed Resend webhook; the log above is the only trace.");
            }
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    private void handleBounce(String svixId, String type, JsonNode data, String rawBody) {
        JsonNode bounce = data == null ? null : data.get("bounce");
        String bounceType = text(bounce, "type");
        String subType = text(bounce, "subType");
        String diagnostic = firstNonBlank(text(bounce, "diagnosticCode"), text(bounce, "message"));
        List<String> recipients = recipients(data);

        String note;
        if ("Permanent".equalsIgnoreCase(bounceType)) {
            int demoted = 0;
            for (String address : recipients) {
                demoted += suppressionService.suppressPermanentBounce(address, svixId, diagnostic);
            }
            note = "Permanent bounce (" + subType + "): suppressed " + recipients.size() + " address(es), unverified "
                    + demoted + " user row(s)";
            log.info("Applied a permanent Resend bounce to {} address(es) (subType={})", recipients.size(), subType);
        } else {
            note = "Non-permanent bounce (" + bounceType + "/" + subType + "): recorded, no addresses suppressed";
            log.info("Recorded a non-permanent Resend bounce (type={}, subType={}). Nothing was suppressed - this "
                    + "is deliberate.", bounceType, subType);
        }
        record(svixId, type, bounceType, true, true, note, rawBody);
    }

    private void handleComplaint(String svixId, String type, JsonNode data, String rawBody) {
        List<String> recipients = recipients(data);
        int optedOut = 0;
        for (String address : recipients) {
            // Resend carries no feedback type, so there is no "not-spam" endorsement
            // to filter out here as there is on the SES path.
            optedOut += suppressionService.recordComplaint(address, svixId, null);
        }
        record(svixId, type, null, true, true,
                "Complaint: applied to " + recipients.size() + " address(es), " + optedOut
                        + " user row(s) opted out of promotional email", rawBody);
        log.info("Applied a Resend complaint to {} address(es)", recipients.size());
    }

    /**
     * Resend refused to send at all - before any receiving server was involved. Recorded and
     * logged loudly, and deliberately NOT turned into a suppression: the documented reasons
     * are overwhelmingly OURS (reached_daily_quota, a revoked API key, an unverified sending
     * domain), and suppressing the recipient for our own quota running out would silence a
     * perfectly good customer forever. A dead recipient shows up as a bounce instead, which
     * IS suppressed. Error level because every one of these means mail is not going out.
     */
    private void handleFailed(String svixId, String type, JsonNode data, String rawBody) {
        String reason = text(data == null ? null : data.get("failed"), "reason");
        List<String> recipients = recipients(data);
        log.error("Resend could not send \"{}\" to {} recipient(s): {}. Nothing was suppressed - check the "
                        + "Resend dashboard (quota, API key, domain verification).",
                text(data, "subject"), recipients.size(), reason == null ? "no reason given" : reason);
        record(svixId, type, reason, true, true,
                "Send failed (" + (reason == null ? "no reason given" : reason) + "): recorded, nothing suppressed",
                rawBody);
    }

    /** {@code data.to} is an array in every documented payload; a bare string is tolerated anyway. */
    private static List<String> recipients(JsonNode data) {
        List<String> addresses = new ArrayList<>();
        JsonNode to = data == null ? null : data.get("to");
        if (to == null) {
            return addresses;
        }
        if (to.isArray()) {
            to.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    addresses.add(node.asText());
                }
            });
        } else if (to.isTextual() && !to.asText().isBlank()) {
            addresses.add(to.asText());
        }
        return addresses;
    }

    private void record(String svixId, String notificationType, String subType, boolean signatureValid,
            boolean processed, String note, String rawBody) {
        eventRepository.save(SesNotificationEvent.builder()
                .messageId(truncate(svixId, 200))
                .messageType(MESSAGE_TYPE)
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

    /** The payload column is jsonb, so a body that is not JSON is wrapped rather than refused by the database. */
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
