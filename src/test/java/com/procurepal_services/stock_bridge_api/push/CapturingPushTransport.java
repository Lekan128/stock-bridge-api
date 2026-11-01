package com.procurepal_services.stock_bridge_api.push;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Stands in for the push services in tests: keeps what was sent; an endpoint containing "/gone/" answers 410. */
public class CapturingPushTransport implements PushTransport {

    public record Sent(String endpoint, Map<String, String> headers, byte[] body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public int send(String endpoint, Map<String, String> headers, byte[] body) {
        sent.add(new Sent(endpoint, Map.copyOf(headers), body));
        return endpoint.contains("/gone/") ? 410 : 201;
    }

    /** What reached {@code endpoint}, waiting up to a few seconds for {@code count} - sending is on another thread. */
    public List<Sent> to(String endpoint, int count) {
        Predicate<Sent> mine = s -> s.endpoint().equals(endpoint);
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sent.stream().filter(mine).count() < count && System.nanoTime() < until) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return sent.stream().filter(mine).toList();
    }
}
