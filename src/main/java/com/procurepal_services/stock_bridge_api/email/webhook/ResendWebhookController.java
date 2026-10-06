package com.procurepal_services.stock_bridge_api.email.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint Resend delivers {@code email.bounced} and {@code email.complained}
 * events to. Register it in the Resend dashboard (Webhooks) as
 * {@code https://<api host>/api/webhooks/resend}, subscribed to those two events, and
 * copy its signing secret into {@code RESEND_WEBHOOK_SECRET}.
 *
 * <p>The body is bound as raw bytes because the Svix signature is an HMAC over the
 * exact bytes sent; a String would be decoded with whatever charset Spring infers and
 * a DTO would not survive at all. Permit-all in {@code SecurityConfig} for the same
 * reason as the SES and Monnify webhooks - Resend has no bearer token of ours - so
 * the signature, checked in {@link ResendWebhookService}, is its only authentication.
 */
@RestController
@RequiredArgsConstructor
public class ResendWebhookController {

    private final ResendWebhookService resendWebhookService;

    @PostMapping("/api/webhooks/resend")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "svix-id", required = false) String svixId,
            @RequestHeader(name = "svix-timestamp", required = false) String svixTimestamp,
            @RequestHeader(name = "svix-signature", required = false) String svixSignature,
            @RequestBody(required = false) byte[] body) {
        return ResponseEntity.status(resendWebhookService.handle(svixId, svixTimestamp, svixSignature, body)).build();
    }
}
