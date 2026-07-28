package com.procurepal_services.stock_bridge_api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.entity.PaymentWebhookEvent;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import com.procurepal_services.stock_bridge_api.repository.PaymentWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles Monnify's server-to-server callback.
 *
 * <h2>The callback is a trigger, never evidence</h2>
 * Even a perfectly-signed webhook is not what marks an order paid. Its only job is
 * to tell us WHICH transaction to go and ask Monnify about; the payment is then
 * applied from the response to our own
 * {@code GET /api/v2/transactions/{transactionReference}} call. That costs one
 * extra round trip and buys three things:
 * <ul>
 *   <li>The webhook body's {@code amountPaid} never has to be trusted, so a forged
 *       callback that survived signature checking still cannot invent a payment.</li>
 *   <li>Monnify documents that {@code monnify-signature} <b>is not sent at all in
 *       sandbox</b>. Without this design, sandbox webhook testing would require
 *       genuinely trusting unsigned input.</li>
 *   <li>Webhook, browser return and reconciliation collapse onto one code path -
 *       {@link PaymentApplicationService#apply} - so there is exactly one place
 *       that can mark an order paid, and therefore exactly one place idempotency
 *       has to hold.</li>
 * </ul>
 *
 * <h2>Everything is logged, including what is rejected</h2>
 * A row goes into {@code payment_webhook_events} for every callback - malformed,
 * unsigned, replayed or good - before any of it is acted on. "We sent you that
 * webhook" is a conversation that needs evidence, and an invalid signature is
 * otherwise completely invisible.
 *
 * <h2>No tenant context on this thread</h2>
 * The endpoint is public, so TenantContext is empty and the Hibernate tenant
 * filter is off for the whole request. Nothing here relies on either: both
 * entities it writes are deliberately not tenant-scoped, and the order is reached
 * by id through the payment row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonnifyWebhookService {

    private static final String PROVIDER = "MONNIFY";
    private static final int NOTE_MAX_LENGTH = 500;

    /** See MonnifyRestClient for why provider payloads get their own plain mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MonnifySignatureVerifier signatureVerifier;
    private final MonnifyClient monnifyClient;
    private final PaymentApplicationService paymentApplicationService;
    private final PaymentWebhookEventRepository webhookEventRepository;

    /**
     * @param rawBody the body exactly as received - see
     *     {@link MonnifySignatureVerifier} for why it must not be a parsed DTO
     * @return whether the callback was accepted for processing; false means the
     *     signature check refused it and the caller should answer 401
     */
    public boolean handle(String rawBody, String providedSignature) {
        JsonNode parsed = tryParse(rawBody);
        String eventType = parsed == null ? null : textOrNull(parsed, "eventType");
        JsonNode eventData = parsed == null ? null : parsed.get("eventData");
        // Monnify nests the transaction under eventData, but not every event shape is
        // documented identically; falling back to the envelope costs nothing and
        // avoids silently ignoring a real payment over a nesting difference.
        String paymentReference = firstNonNull(
                eventData == null ? null : textOrNull(eventData, "paymentReference"),
                parsed == null ? null : textOrNull(parsed, "paymentReference"));
        String transactionReference = firstNonNull(
                eventData == null ? null : textOrNull(eventData, "transactionReference"),
                parsed == null ? null : textOrNull(parsed, "transactionReference"));

        boolean signatureValid = signatureVerifier.isValid(rawBody, providedSignature);

        PaymentWebhookEvent event = PaymentWebhookEvent.builder()
                .provider(PROVIDER)
                .eventType(truncate(eventType, 60))
                .paymentReference(truncate(paymentReference, 100))
                .transactionReference(truncate(transactionReference, 100))
                .signatureValid(signatureValid)
                .processed(false)
                .payload(storablePayload(rawBody))
                .build();

        if (!signatureValid && signatureVerifier.isSignatureRequired()) {
            event.setProcessingNote(truncate(
                    providedSignature == null || providedSignature.isBlank()
                            ? "Rejected: no monnify-signature header"
                            : "Rejected: monnify-signature did not match",
                    NOTE_MAX_LENGTH));
            webhookEventRepository.save(event);
            log.warn("Rejected a Monnify webhook with an invalid signature (paymentReference={}, eventType={})",
                    paymentReference, eventType);
            return false;
        }

        if (!signatureValid) {
            // Accepted only because signature enforcement is switched off - the
            // sandbox case. Recorded as signature_valid=false regardless, so this is
            // auditable and a production deployment running like this is obvious.
            log.warn("Processing an UNSIGNED Monnify webhook because app.monnify.require-webhook-signature is "
                    + "false (paymentReference={}). This must never be the case in production.", paymentReference);
        }

        event.setProcessingNote(truncate(process(paymentReference, transactionReference, eventType), NOTE_MAX_LENGTH));
        event.setProcessed(true);
        webhookEventRepository.save(event);
        return true;
    }

    /**
     * Runs synchronously, before the 200 is returned. Monnify's guidance is to
     * acknowledge first and process afterwards, and that is the right advice for a
     * slow job - but here "processing" is one provider call and one short
     * transaction, and doing it inline means a callback that 200s has demonstrably
     * been applied. If it does time out, the reconciliation sweep is the safety net
     * that makes the tradeoff safe either way.
     *
     * @return the note recorded against the event
     */
    private String process(String paymentReference, String transactionReference, String eventType) {
        if (paymentReference == null && transactionReference == null) {
            log.warn("Monnify webhook carried no reference at all (eventType={}) - nothing to verify", eventType);
            return "Ignored: no paymentReference or transactionReference in payload";
        }

        if (!monnifyClient.isConfigured()) {
            // Cannot re-verify, so refuse to conclude anything. The attempt stays
            // PENDING and the sweep retries once credentials are restored.
            log.error("Monnify webhook for paymentReference={} could not be verified - Monnify is unconfigured",
                    paymentReference);
            return "Deferred: Monnify is not configured, left for reconciliation";
        }

        MonnifyTransactionStatus status;
        try {
            // Ask the provider directly rather than believing the body. If the
            // callback only gave us a paymentReference, that is still the reference
            // Monnify's transactionReference was minted against on our payment row -
            // but we have no way to look it up here without one, so require it.
            if (transactionReference == null) {
                log.warn("Monnify webhook for paymentReference={} had no transactionReference - cannot verify",
                        paymentReference);
                return "Deferred: no transactionReference to verify against, left for reconciliation";
            }
            status = monnifyClient.getTransactionStatus(transactionReference);
        } catch (MonnifyApiException e) {
            // "We do not know" - never resolved as either outcome here. Swallowed so
            // the callback still gets a 200 (Monnify retries are not what fixes
            // this); the sweep is.
            log.error("Could not verify Monnify webhook for paymentReference={}: {}",
                    paymentReference, e.getMessage());
            return "Deferred: provider verification failed, left for reconciliation";
        }

        // Trust our own record of which reference this is, falling back to the
        // callback's only if the verify response omitted it.
        String reference = firstNonNull(status.paymentReference(), paymentReference);
        if (reference == null) {
            return "Ignored: provider returned no paymentReference";
        }

        PaymentApplicationOutcome outcome =
                paymentApplicationService.apply(reference, status, PaymentVerificationSource.WEBHOOK);
        log.info("Monnify webhook processed paymentReference={} eventType={} outcome={}",
                reference, eventType, outcome);
        return outcome.name();
    }

    // ------------------------------------------------------------------------

    private JsonNode tryParse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("Monnify webhook body was not parseable JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * {@code payment_webhook_events.payload} is a non-null JSONB column, so a
     * malformed body cannot be stored verbatim - and dropping it would destroy the
     * one artefact needed to work out what a broken sender actually sent. Wrapping
     * it keeps the bytes and satisfies the column.
     */
    private String storablePayload(String rawBody) {
        if (tryParse(rawBody) != null) {
            return rawBody;
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("_unparseable", true);
        wrapper.put("_raw", rawBody == null ? "" : rawBody);
        return wrapper.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonNull(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
