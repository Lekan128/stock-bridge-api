package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Caps how often one caller may submit the public vendor waitlist form.
 *
 * <h2>Deliberately the same mechanism as EmailVerificationRateLimiter</h2>
 * Read that class first - the rolling-window reasoning, the per-deque
 * synchronization, the sweep threshold and the never-throws contract are all
 * copied from it verbatim in intent, and its Javadoc argues each at length rather
 * than repeating it here. This is not a second rate-limiting scheme; it is the
 * one this codebase already has, applied to the one other endpoint that makes
 * this server send mail on a stranger's say-so.
 *
 * <p>The two differences are both forced by the endpoint being unauthenticated,
 * and both are worth naming.
 *
 * <h2>Difference 1: the key is a String, and there are two of them</h2>
 * The verification limiter keys by user id because its caller is signed in. This
 * one has no principal at all, so the service checks two keys per submission (see
 * {@link VendorWaitlistService}):
 * <ul>
 *   <li><strong>the client IP</strong>, which is the actual abuse vector - one
 *       script POSTing the form in a loop, filling the review queue with garbage
 *       and mailing an arbitrary address every time;</li>
 *   <li><strong>the submitted email</strong>, which bounds the other shape of the
 *       same attack - the same victim address named repeatedly from a rotating
 *       set of addresses, where every individual IP looks innocent.</li>
 * </ul>
 * Neither alone is enough and neither is airtight. Keys are namespaced by their
 * caller ({@code "ip:"} / {@code "email:"}) so a submitted email that happens to
 * read like an IP cannot spend an IP's budget.
 *
 * <h2>Difference 2: what an IP actually is behind a proxy</h2>
 * {@code HttpServletRequest.getRemoteAddr()} is the load balancer, not the user,
 * unless the app is configured to trust a forwarded header. This deploy runs
 * behind one (see DEPLOYMENT.md), so in production a large share of traffic can
 * collapse onto a small number of source addresses and the IP budget is then
 * shared by strangers. That is stated rather than worked around because the
 * honest fix - trusting {@code X-Forwarded-For} - is a decision about the whole
 * application's request handling, not something one feature should switch on for
 * itself, and a spoofable header would make this limit weaker rather than
 * stronger. The email key is what carries the load when the IP key is coarse.
 *
 * <h2>What this honestly does not cover</h2>
 * Everything {@code EmailVerificationRateLimiter} lists - per instance, forgotten
 * on restart - applies here identically, and to it add: an attacker with a
 * botnet, or a rotating set of addresses, gets a fresh budget per source. This
 * limit turns "unbounded" into "a handful per source", which is what it is for.
 * It is not a bot defence. If one is ever needed the right shape is a CAPTCHA or
 * a WAF rule in front of the endpoint, not a bigger map in here.
 *
 * <h2>Never throws, and a consumed slot stays consumed</h2>
 * Returns a {@link Verdict} rather than throwing, so the service turns a refusal
 * into an ordinary HTTP response instead of an exception crossing a transaction
 * boundary - the hazard {@code EmailRecipients} documents at length. And a slot is
 * spent whether or not the submission that follows succeeds, which is the safe
 * direction: counting only successful inserts would let a caller hammer a form
 * that is failing validation without limit.
 */
@Component
@RequiredArgsConstructor
public class VendorWaitlistRateLimiter {

    /** See EmailVerificationRateLimiter.SWEEP_THRESHOLD - same number, same reasoning. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final VendorWaitlistProperties properties;

    private final Map<String, Deque<Instant>> recentSubmissions = new ConcurrentHashMap<>();

    /**
     * Consumes a slot for {@code key} if one is free.
     *
     * @param key an already-namespaced identity - {@code "ip:..."} or
     *     {@code "email:..."}. Lowercased here so two spellings of one address do
     *     not get two budgets.
     */
    public Verdict tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            // Nothing to key on - a request with no resolvable remote address. Refuse
            // rather than wave through: this is the one branch an attacker would
            // aim for if waving through were the alternative.
            return Verdict.refused(properties.submitWindow());
        }

        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.submitWindow());

        if (recentSubmissions.size() > SWEEP_THRESHOLD) {
            sweep(cutoff);
        }

        Deque<Instant> history =
                recentSubmissions.computeIfAbsent(key.trim().toLowerCase(Locale.ROOT), ignored -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
                history.pollFirst();
            }
            if (history.size() >= properties.submitLimit()) {
                Duration retryAfter = Duration.between(cutoff, history.peekFirst());
                return Verdict.refused(retryAfter.isNegative() ? Duration.ZERO : retryAfter);
            }
            history.addLast(now);
            return Verdict.allow();
        }
    }

    /** See EmailVerificationRateLimiter.sweep - tolerant of racing tryAcquire for the same reason. */
    private void sweep(Instant cutoff) {
        recentSubmissions.entrySet().removeIf(entry -> {
            Deque<Instant> history = entry.getValue();
            synchronized (history) {
                return history.isEmpty() || !history.peekLast().isAfter(cutoff);
            }
        });
    }

    /**
     * The decision, plus how long until a slot frees up.
     *
     * <p>Named {@code allow} rather than {@code allowed} for the compile reason
     * {@code EmailVerificationRateLimiter.Verdict} spells out: a record's accessor
     * for the {@code allowed} component already owns that name.
     */
    public record Verdict(boolean allowed, Duration retryAfter) {

        static Verdict allow() {
            return new Verdict(true, Duration.ZERO);
        }

        static Verdict refused(Duration retryAfter) {
            return new Verdict(false, retryAfter);
        }
    }
}
