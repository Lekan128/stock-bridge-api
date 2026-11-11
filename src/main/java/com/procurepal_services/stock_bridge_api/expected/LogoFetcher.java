package com.procurepal_services.stock_bridge_api.expected;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fetches a company's logo so the PDF can draw it (PURCHASE_ORDER_PLAN.md section 4.1).
 *
 * <p>Kept in memory by URL: a logo's URL changes whenever the file does (each upload is a new
 * object), so a cached entry can never be stale, and a burst of people opening one order builds
 * the PDF from one download. The cache is small and bounded. A logo that cannot be fetched is
 * simply left off the page - a purchase order must never fail to draw over a picture.
 */
@Component
@Slf4j
public class LogoFetcher {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ENTRIES = 50;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** The logo's bytes, or null when there is none or it could not be fetched. */
    public byte[] fetch(String url) {
        if (url == null || !url.startsWith("https://")) {
            return null;
        }
        synchronized (cache) {
            byte[] cached = cache.get(url);
            if (cached != null) {
                return cached;
            }
        }
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                    return null;
                }
                synchronized (cache) {
                    cache.put(url, bytes);
                }
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Could not fetch a logo for a purchase order PDF: {}", e.getMessage());
            return null;
        }
    }
}
