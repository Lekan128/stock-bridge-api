package com.procurepal_services.stock_bridge_api.email.webhook;

import org.springframework.http.HttpStatus;

/**
 * What happened to one SNS delivery, and therefore what status code SNS is told.
 *
 * <h2>The status code is a control signal, not a description</h2>
 * SNS retries anything that is not 2xx, on a backoff, for up to 23 attempts over
 * several hours, and then drops the message. Both halves of that matter and they
 * pull in opposite directions, which is why this is an enum with the mapping
 * attached rather than a scattering of {@code ResponseEntity.status(...)} calls.
 *
 * <p><strong>Retrying must be reserved for things a retry can fix.</strong> A
 * message we understood and acted on, a duplicate we deliberately ignored, an SES
 * event type we do not care about - all of those are 2xx, because they are finished.
 * Answering anything else would have SNS redeliver them for hours, and each
 * redelivery costs another signature verification and another audit row while
 * changing nothing.
 *
 * <p><strong>But a signature failure must never be 2xx.</strong> If our own
 * configuration is wrong - a pinned topic ARN with a typo, a certificate we cannot
 * reach - then every genuine bounce notification is being refused, and 2xx would
 * tell SNS to discard them permanently and silently. A non-2xx makes AWS keep them
 * queued while somebody notices. It is also the honest answer: we did not accept the
 * message.
 */
public enum SesNotificationOutcome {

    /**
     * Understood and finished with. Covers rather more than "something changed":
     * a transient bounce that deliberately changed nothing, a duplicate that was
     * skipped, a Delivery event that was ignored, and an unrecognised SNS type are
     * all successes as far as the protocol is concerned.
     */
    ACCEPTED(HttpStatus.OK),

    /**
     * 401, matching {@code PaymentController}'s answer to a bad Monnify signature.
     * An authentication failure rather than a processing outcome, and the direction
     * that keeps genuine notifications queued at AWS if the fault turns out to be
     * ours.
     */
    REJECTED_SIGNATURE(HttpStatus.UNAUTHORIZED),

    /**
     * 403 rather than 401: the message authenticated perfectly - AWS really did sign
     * it - and was refused on authorization, because it came from a topic this
     * deployment does not accept. Distinguishing the two in the status and in the
     * audit note is what lets an operator tell "somebody is forging messages"
     * (impossible, and therefore a bug) from "somebody has pointed a second topic at
     * us" (entirely possible, and the reason the pin exists).
     */
    REJECTED_TOPIC(HttpStatus.FORBIDDEN),

    /**
     * 400: a URL in the body pointed somewhere we refuse to send a request. Not
     * retryable - the same body will be refused identically - and not an
     * authentication failure, so neither 2xx nor 401 is right. See
     * {@link SnsEndpointGuard}.
     */
    REFUSED_ENDPOINT(HttpStatus.BAD_REQUEST),

    /** 400: the body was not parseable JSON, or carried no SNS envelope at all. */
    MALFORMED(HttpStatus.BAD_REQUEST),

    /**
     * 503: we believed the message and could not apply it - the database was
     * unavailable, most likely. The one case where a retry is genuinely the right
     * remedy, and the reason this is not folded into a generic 500.
     */
    TEMPORARY_FAILURE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    SesNotificationOutcome(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
