package com.procurepal_services.stock_bridge_api.email.verification;

import java.time.Duration;

/**
 * A freshly issued verification link and how long it lasts, as two already-rendered
 * strings.
 *
 * <h2>Why a record and not two String parameters</h2>
 * Both values come from the same act of issuing a token and are meaningless apart:
 * a URL without its expiry produces an email that cannot say when the link dies,
 * and an expiry without a URL produces a sentence about nothing. Passing them
 * separately through {@code EmailNotificationService} and into three template
 * overloads would be four signatures with two adjacent Strings in each - the shape
 * that eventually gets called with the arguments the wrong way round, silently,
 * because both sides compile.
 *
 * <h2>Why nullable is the caller's problem and not a second type</h2>
 * There is no token when {@code app.email.app-base-url} is unset, or when the user
 * has no plausible address to send to. Every consumer treats null as "send the
 * email without a confirm block", which is exactly what the templates already do
 * with a blank URL, so an {@code Optional} here would only move the same null check
 * one call further out. The methods that can return nothing say so in their
 * javadoc.
 *
 * @param url the absolute frontend URL carrying the raw token, or blank if none
 * @param expiresIn human-readable, e.g. "24 hours" - see
 *     {@link #humanise(Duration)} for why it is rendered here rather than in the
 *     template
 */
public record VerificationLink(String url, String expiresIn) {

    /**
     * Turns a configured TTL into the phrase an email can print.
     *
     * <p>Rendering happens here, once, rather than in each template, because the
     * templates are pure string builders with no access to configuration and
     * because the alternative - printing the raw {@code Duration}, which formats as
     * {@code PT24H} - is exactly the sort of thing that reaches a customer's inbox
     * and is only noticed by a customer.
     *
     * <p>Deliberately coarse. It rounds to whole hours or whole days and never
     * emits a compound phrase, because the number's only job is to tell a reader
     * roughly how much time they have. "23 hours and 59 minutes" is precision the
     * reader cannot use and that stops being true one second after it is written.
     */
    public static String humanise(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return "";
        }
        long hours = ttl.toHours();
        if (hours >= 48 && hours % 24 == 0) {
            return ttl.toDays() + " days";
        }
        if (hours >= 24 && hours < 48) {
            // The overwhelmingly common configured value. "24 hours" reads more
            // naturally to somebody deciding whether to act now than "1 day" does.
            return "24 hours";
        }
        if (hours >= 1) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = Math.max(1, ttl.toMinutes());
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
