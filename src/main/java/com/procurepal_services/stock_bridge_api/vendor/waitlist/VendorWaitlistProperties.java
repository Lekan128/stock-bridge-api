package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two numbers bounding an endpoint any stranger on the internet can POST to,
 * and nothing else. Same shape, same discipline and the same reasoning as
 * {@code EmailVerificationProperties}: everything with a single defensible value
 * stays a constant in the code, because a knob nobody should turn is a knob
 * somebody eventually turns.
 *
 * <h2>Why these are here and not on EmailProperties</h2>
 * {@code app.email.vendor-waitlist-address} genuinely belongs there - it is an
 * address SES is handed, which is what that record is about. A rate limit is not:
 * it describes how one flow behaves, feeds neither {@code isConfigured()} nor the
 * SES request, and would be dead weight on a record {@code EmailSender} reads on
 * every single send. That is the split {@code EmailVerificationProperties} makes
 * and it applies here unchanged.
 *
 * <h2>Both have working defaults</h2>
 * A deploy that never sets either behaves correctly. These exist only so that an
 * operator watching somebody pump the public form can tighten it without a
 * release - which is a real Saturday-afternoon scenario for an unauthenticated,
 * mail-sending, row-creating endpoint.
 */
@ConfigurationProperties(prefix = "app.vendor-waitlist")
public record VendorWaitlistProperties(Integer submitLimit, Duration submitWindow) {

    /**
     * Three submissions per window. A real business applies once; the second and
     * third cover the ordinary reasons a human resubmits - they mistyped their
     * phone number, the tab looked like it had not gone through, they wanted to add
     * to the notes. A fourth in the same hour from the same place is not a business
     * applying.
     *
     * <p>Chosen to match {@code app.email.verification.resend-limit} rather than
     * derived independently: both bound "how many emails may one party make this
     * server send", and two different numbers for one question would be two numbers
     * to reason about during an incident.
     */
    private static final int DEFAULT_SUBMIT_LIMIT = 3;

    /**
     * One hour, rolling rather than a calendar bucket - see
     * {@link VendorWaitlistRateLimiter} for why that distinction is what makes the
     * limit mean what it says at every instant rather than only on average.
     */
    private static final Duration DEFAULT_SUBMIT_WINDOW = Duration.ofHours(1);

    /**
     * Boxed types and a compact constructor rather than {@code :defaults} in
     * application.yml, so each default sits next to the paragraph explaining it. A
     * value that is absent, zero or negative falls back: "0" in an environment
     * variable is a fat-fingered override, not a request to close the public form
     * to everybody.
     */
    public VendorWaitlistProperties {
        submitLimit = submitLimit == null || submitLimit < 1 ? DEFAULT_SUBMIT_LIMIT : submitLimit;
        submitWindow = submitWindow == null || submitWindow.isZero() || submitWindow.isNegative()
                ? DEFAULT_SUBMIT_WINDOW
                : submitWindow;
    }
}
