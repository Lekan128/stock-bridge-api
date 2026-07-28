package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentRequest;
import com.procurepal_services.stock_bridge_api.payment.dto.InitializePaymentResponse;
import com.procurepal_services.stock_bridge_api.payment.dto.PaymentVerificationResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Monnify payment surface - see MonnifyPaymentService and MonnifyWebhookService for the reasoning. */
@RestController
@RequiredArgsConstructor
public class PaymentController {

    /**
     * Lower-cased because HTTP header names are case-insensitive and Spring matches
     * them that way; Monnify sends it lower-cased.
     */
    private static final String SIGNATURE_HEADER = "monnify-signature";

    private final MonnifyPaymentService monnifyPaymentService;
    private final MonnifyWebhookService monnifyWebhookService;

    /**
     * PLACE_ORDERS rather than VIEW_ORDERS: opening a checkout is part of placing
     * an order, and the ownership check inside the service is what stops a buyer
     * paying for someone else's.
     */
    @PostMapping("/api/payments/monnify/initialize")
    @PreAuthorize("hasAuthority('PLACE_ORDERS')")
    public InitializePaymentResponse initialize(
            @Valid @RequestBody InitializePaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return monnifyPaymentService.initialize(request.orderId(), principal.getClientId());
    }

    /**
     * Public, called by Monnify. Its ONLY authentication is the signature over the
     * body, which is why the body is bound as a raw String.
     *
     * <p>Binding {@code @RequestBody String} is load-bearing, not laziness: the
     * signature is an HMAC over the exact bytes sent, so letting Spring
     * deserialize into a DTO and re-serializing to recompute it would change key
     * order, whitespace and number formatting, and every signature would fail. See
     * {@link MonnifySignatureVerifier}.
     *
     * <p>{@code required = false} so an empty or absent body reaches the handler
     * and gets logged as a malformed callback, rather than being turned into a 400
     * by Spring before there is any record of it.
     *
     * <p>Answers 200 on anything it accepted - the acknowledgement Monnify's docs
     * ask for, including for a replay it deliberately did nothing with. An invalid
     * signature answers 401: it is an authentication failure, not a processing
     * outcome, and 401 makes Monnify retry, which is the safe direction to fail if
     * our own secret key is ever misconfigured.
     */
    @PostMapping("/api/payments/monnify/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {
        boolean accepted = monnifyWebhookService.handle(rawBody, signature);
        return accepted
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /**
     * The buyer's return page calls this. It triggers a server-side re-verify; the
     * outcome comes from Monnify, never from the query string the browser arrived
     * with.
     *
     * <p>VIEW_ORDERS, plus an ownership assertion in the service - {@code payments}
     * has no client_id, so nothing else is scoping this read.
     */
    @GetMapping("/api/payments/{paymentReference}/verify")
    @PreAuthorize("hasAuthority('VIEW_ORDERS')")
    public PaymentVerificationResponse verify(
            @PathVariable String paymentReference,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return monnifyPaymentService.verifyForBuyer(paymentReference, principal.getClientId());
    }
}
