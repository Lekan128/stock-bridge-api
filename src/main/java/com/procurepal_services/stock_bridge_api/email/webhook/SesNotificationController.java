package com.procurepal_services.stock_bridge_api.email.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint AWS SNS delivers SES bounce and complaint notifications to.
 *
 * <h2>Why the body is bound as a raw String</h2>
 * Two independent reasons, either of which would be sufficient.
 *
 * <p><strong>SNS lies about its content type.</strong> It posts JSON with
 * {@code Content-Type: text/plain; charset=UTF-8} - not
 * {@code application/json} - and has done for the life of the service. Spring
 * selects a message converter by content type, so a JSON-shaped {@code @RequestBody}
 * DTO here would not bind at all: every notification would be answered 415 before
 * any handler ran, SNS would retry each one for hours and then drop it, and the
 * application log would show nothing, because no code of ours would have executed.
 * {@code String} binds through {@code StringHttpMessageConverter}, which accepts any
 * content type, so the body arrives whatever SNS calls it.
 *
 * <p><strong>Signature verification needs the fields, not a DTO.</strong> Unlike the
 * Monnify webhook - whose signature is an HMAC over the exact bytes, and which
 * therefore binds a String so the bytes survive - SNS signs a canonical document
 * assembled from named envelope fields. That needs a parse, and it needs a parse
 * that includes fields no DTO would model, so the parsing happens in
 * {@link SesNotificationService} where the verification does. See
 * {@link SnsSignatureVerifier}.
 *
 * <h2>Its only authentication is the signature</h2>
 * Registered permit-all in {@code SecurityConfig}, like the Monnify webhook and for
 * the same reason: AWS has no bearer token of ours to present. There is no
 * authenticated principal, so {@code TenantContext} is empty and the Hibernate
 * tenant filter is disabled for the whole request - which is exactly what the
 * cross-tenant writes downstream require, and is documented at each of them.
 *
 * <p>CSRF needs no exemption and must not be given one: it is disabled
 * application-wide for a stateless bearer-token API that keeps no cookies, so there
 * is no ambient credential for a cross-site POST to borrow.
 *
 * <h2>Status codes are a control signal</h2>
 * SNS retries any non-2xx for hours. So anything understood and finished with -
 * including a transient bounce that deliberately changed nothing, a duplicate, and
 * an SES event type this endpoint ignores - answers 200, and only genuinely
 * unfinished or refused work answers otherwise. A signature failure is never 2xx:
 * if our own configuration is what is wrong, 2xx would have AWS discard every real
 * bounce notification silently. {@link SesNotificationOutcome} holds the mapping and
 * the full argument.
 */
@RestController
@RequiredArgsConstructor
public class SesNotificationController {

    private final SesNotificationService sesNotificationService;

    /**
     * <pre>
     * POST /api/webhooks/ses/notifications
     * Content-Type: text/plain; charset=UTF-8
     * x-amz-sns-message-type: Notification | SubscriptionConfirmation | UnsubscribeConfirmation
     *
     * { the SNS envelope }
     * </pre>
     *
     * <p>The {@code x-amz-sns-message-type} header is deliberately not read. It is
     * unauthenticated - it sits outside the signed canonical string, so an attacker
     * could set it to anything - whereas the envelope's own {@code Type} field is
     * one of the fields the signature covers. Dispatching on the header would mean
     * dispatching on the one copy of that value nobody signed.
     *
     * <p>{@code required = false} so an empty or absent body reaches the handler and
     * is recorded as a malformed delivery, rather than being turned into a 400 by
     * Spring before there is any evidence it arrived.
     *
     * <p>No {@code consumes} attribute, on purpose: constraining it would reintroduce
     * the content-type problem described on the class.
     *
     * @return 200 for anything accepted and understood; 401 for a signature that did
     *     not verify; 403 for a valid signature from an unexpected topic; 400 for a
     *     malformed body or a refused URL; 503 if it could not be applied.
     */
    @PostMapping("/api/webhooks/ses/notifications")
    public ResponseEntity<Void> receive(@RequestBody(required = false) String rawBody) {
        SesNotificationOutcome outcome = sesNotificationService.handle(rawBody);
        return ResponseEntity.status(outcome.status()).build();
    }
}
