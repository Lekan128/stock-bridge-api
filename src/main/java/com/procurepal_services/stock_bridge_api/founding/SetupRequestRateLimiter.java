package com.procurepal_services.stock_bridge_api.founding;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bounds how many setup requests one caller can make: the endpoint is public and creates a row the
 * team acts on. Keyed by source address and by WhatsApp number, the way VendorWaitlistRateLimiter
 * keys by address and email. In memory, per instance: a deterrent, not a wall.
 */
@Component
@RequiredArgsConstructor
class SetupRequestRateLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final FoundingOfferProperties properties;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    /** Takes a slot for {@code key}, or says how long until one frees up. */
    Duration tryAcquire(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.submitWindow());
        if (recent.size() > SWEEP_THRESHOLD) {
            recent.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff);
                }
            });
        }
        Deque<Instant> history = recent.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
                history.pollFirst();
            }
            if (history.size() >= properties.submitLimit()) {
                Duration wait = Duration.between(cutoff, history.peekFirst());
                return wait.isNegative() ? Duration.ZERO : wait;
            }
            history.addLast(now);
            return null;
        }
    }
}
