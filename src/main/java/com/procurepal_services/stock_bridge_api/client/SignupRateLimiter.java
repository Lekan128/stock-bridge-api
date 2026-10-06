package com.procurepal_services.stock_bridge_api.client;

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
 * Rolling-window limits on signup, per submitted email address and per client IP - see
 * {@link SignupProperties} for the numbers and why there are two. Same in-process design as
 * {@code PasswordResetRateLimiter}, with the same caveats: per instance, forgotten on restart,
 * and a backstop rather than a bot defence.
 */
@Component
@RequiredArgsConstructor
public class SignupRateLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final SignupProperties properties;

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    /**
     * Records one signup against both keys, or refuses without recording either - so a request
     * refused on one key does not use up the other's allowance.
     *
     * @return how long until a signup would be allowed, or zero if this one is
     */
    public synchronized Duration tryAcquire(String address, String clientIp) {
        Instant now = Instant.now();
        if (recent.size() > SWEEP_THRESHOLD) {
            Instant oldest = now.minus(longestWindow());
            recent.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                    || !entry.getValue().peekLast().isAfter(oldest));
        }
        String emailKey = "email:" + (address == null ? "" : address.trim().toLowerCase(Locale.ROOT));
        String ipKey = "ip:" + (clientIp == null ? "unknown" : clientIp);

        Duration wait = waitFor(emailKey, properties.emailLimit(), now.minus(properties.emailWindow()));
        Duration ipWait = waitFor(ipKey, properties.ipLimit(), now.minus(properties.ipWindow()));
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

    private Duration longestWindow() {
        return properties.emailWindow().compareTo(properties.ipWindow()) >= 0
                ? properties.emailWindow()
                : properties.ipWindow();
    }
}
