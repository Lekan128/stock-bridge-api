package com.procurepal_services.stock_bridge_api.email.verification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three numbers in this flow that an operator might legitimately want to move,
 * and nothing else. Everything with a single defensible value - the token length,
 * the hash, the URL path - is a constant in the code, because a knob nobody should
 * turn is a knob somebody eventually turns.
 *
 * <h2>Why a separate record rather than three more components on EmailProperties</h2>
 * {@link com.procurepal_services.stock_bridge_api.email.EmailProperties} answers
 * "can this deploy send mail at all", and every one of its fields feeds
 * {@code isConfigured()} or the SES request itself. These three answer "how does
 * one particular flow behave", feed neither, and would be dead weight on the record
 * that {@code EmailSender} reads on every send. Binding them under
 * {@code app.email.verification} keeps them findable next to their siblings while
 * leaving that record about the transport.
 *
 * <h2>All three have defaults, unlike the SES identity</h2>
 * {@code EmailProperties.fromAddress} deliberately has no default because a wrong
 * value there is a silent remote failure. Nothing here has that property: every
 * value below is safe, self-consistent and correct for a deploy that never thinks
 * about it. A misconfigured deploy gets a 24-hour link and three resends an hour,
 * which is the same thing a configured one gets.
 */
@ConfigurationProperties(prefix = "app.email.verification")
public record EmailVerificationProperties(
        Duration tokenTtl, Integer resendLimit, Duration resendWindow) {

    /**
     * 24 hours. Long enough that a link sent on a Friday evening still works when
     * somebody opens their inbox on Saturday morning - the single most common real
     * pattern - and short enough that a forwarded, archived or screenshotted link
     * is dead by the time it leaks.
     *
     * <p>The trade being made is worth naming, because it runs the other way to a
     * password reset. A password-reset token grants a credential and is
     * conventionally given 15-60 minutes; this one grants a flag that says "this
     * inbox is reachable", which is the very thing an attacker holding the link
     * has already had to demonstrate in order to hold it. The blast radius of a
     * stolen verification link is therefore an address the thief already controls
     * being marked reachable - not an account takeover - so the cost of a longer
     * window is small and the cost of a shorter one (users who miss it and have to
     * find the resend button) is real and immediate.
     *
     * <p>Do not raise this past a few days. Past that the address the token was
     * bound to has meaningfully drifted from the address on the account, and the
     * binding check starts rejecting more links than the expiry does.
     */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(24);

    /**
     * Three sends per window. Enough to cover the genuine reasons a person clicks
     * resend - it went to spam, they mistyped and fixed their address, the first
     * one crossed with a mail outage - and few enough that the endpoint is useless
     * as a way to bombard an inbox.
     */
    private static final int DEFAULT_RESEND_LIMIT = 3;

    /**
     * One hour, rolling. A fixed calendar hour would let a caller spend the whole
     * budget at 10:59 and the whole next budget at 11:01; see
     * {@link EmailVerificationRateLimiter} for how the window is actually kept.
     */
    private static final Duration DEFAULT_RESEND_WINDOW = Duration.ofHours(1);

    /**
     * Boxed types and a compact constructor rather than primitives with
     * {@code :defaults} in application.yml, so the default lives here next to the
     * paragraph explaining it rather than in a YAML comment nobody reads. A value
     * that is absent, zero or negative falls back - "0" in an environment variable
     * is a fat-fingered override, not a request for a token that expires
     * instantly.
     */
    public EmailVerificationProperties {
        tokenTtl = positiveOr(tokenTtl, DEFAULT_TOKEN_TTL);
        resendWindow = positiveOr(resendWindow, DEFAULT_RESEND_WINDOW);
        resendLimit = resendLimit == null || resendLimit < 1 ? DEFAULT_RESEND_LIMIT : resendLimit;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
