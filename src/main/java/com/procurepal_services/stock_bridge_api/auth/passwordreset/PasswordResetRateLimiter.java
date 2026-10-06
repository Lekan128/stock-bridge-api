package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limits on reset requests, per email address and per client address.
 * The same in-process design as {@code EmailVerificationRateLimiter}, with the same
 * caveat: limits are per instance, which is acceptable at today's single instance
 * and is a backstop, not the security of the flow (that is the token).
 *
 * <p>Applied whether or not the email belongs to anybody, so a refusal reveals nothing
 * about who has an account.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetRateLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final PasswordResetProperties properties;

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    /**
     * Records one request against both keys, or refuses without recording. Both are
     * checked before either is recorded, so a request refused on one key does not use
     * up the other's allowance.
     *
     * @return how long until a request would be allowed, or zero if this one is
     */
    public synchronized Duration tryAcquire(String address, String clientIp) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.window());
        if (recent.size() > SWEEP_THRESHOLD) {
            recent.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                    || !entry.getValue().peekLast().isAfter(cutoff));
        }
        String emailKey = "email:" + address;
        String ipKey = "ip:" + (clientIp == null ? "unknown" : clientIp);
        Duration wait = waitFor(emailKey, properties.emailLimit(), cutoff);
        Duration ipWait = waitFor(ipKey, properties.ipLimit(), cutoff);
        if (ipWait.compareTo(wait) > 0) {
            wait = ipWait;
        }
        if (!wait.isZero()) {
            return wait;
        }
        recent.computeIfAbsent(emailKey, key -> new ArrayDeque<>()).addLast(now);
        recent.computeIfAbsent(ipKey, key -> new ArrayDeque<>()).addLast(now);
        return Duration.ZERO;
    }

    private Duration waitFor(String key, int limit, Instant cutoff) {
        Deque<Instant> history = recent.get(key);
        if (history == null) {
            return Duration.ZERO;
        }
        while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
            history.pollFirst();
        }
        if (history.size() < limit) {
            return Duration.ZERO;
        }
        Duration wait = Duration.between(cutoff, history.peekFirst());
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }
}
