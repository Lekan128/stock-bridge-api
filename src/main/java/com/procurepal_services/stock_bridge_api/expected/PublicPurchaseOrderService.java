package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import com.procurepal_services.stock_bridge_api.expected.dto.PublicPurchaseOrderResponse;
import com.procurepal_services.stock_bridge_api.repository.ExpectedDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The page and PDF a supplier opens from a link, without an account (PURCHASE_ORDER_PLAN.md
 * phase 5). The long random token in the link is the only thing that scopes it, so every failure to
 * match one answers the same way - "not found" - and the caller is limited per address.
 *
 * <p>The PDF is drawn from the order's frozen copy and kept in memory for a minute, so a burst of
 * people tapping one WhatsApp link builds it once. It is never written anywhere.
 */
@Service
@RequiredArgsConstructor
public class PublicPurchaseOrderService {

    private static final int LIMIT_PER_MINUTE = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration PDF_KEPT_FOR = Duration.ofMinutes(1);
    private static final int MAX_CACHED = 200;

    private final ExpectedDeliveryRepository repository;
    private final PurchaseOrderPdf purchaseOrderPdf;
    private final LogoFetcher logoFetcher;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private final Map<String, CachedPdf> pdfs = new ConcurrentHashMap<>();

    /** A drawn PDF and when to forget it. */
    private record CachedPdf(byte[] bytes, Instant forgetAt) {
    }

    public record PdfFile(byte[] bytes, String filename) {
    }

    @Transactional(readOnly = true)
    public PublicPurchaseOrderResponse view(String token, String callerAddress) {
        limit(callerAddress);
        ExpectedDelivery order = find(token);
        return PublicPurchaseOrderResponse.of(
                snapshot(order),
                order.getStatus() == ExpectedDeliveryStatus.CANCELLED,
                "/api/public/purchase-orders/" + token + "/pdf");
    }

    @Transactional(readOnly = true)
    public PdfFile pdf(String token, String callerAddress) {
        limit(callerAddress);
        ExpectedDelivery order = find(token);
        if (order.getStatus() == ExpectedDeliveryStatus.CANCELLED) {
            throw new ExpectedDeliveryException(HttpStatus.GONE, "This order was called off.");
        }
        PurchaseOrderSnapshot snapshot = snapshot(order);
        String key = token + ":" + order.getRevision();
        Instant now = Instant.now();
        CachedPdf cached = pdfs.get(key);
        byte[] bytes;
        if (cached != null && cached.forgetAt().isAfter(now)) {
            bytes = cached.bytes();
        } else {
            bytes = purchaseOrderPdf.render(snapshot, logoFetcher.fetch(snapshot.buyer().logoUrl()));
            if (pdfs.size() >= MAX_CACHED) {
                pdfs.values().removeIf(entry -> !entry.forgetAt().isAfter(now));
                if (pdfs.size() >= MAX_CACHED) {
                    pdfs.clear();
                }
            }
            pdfs.put(key, new CachedPdf(bytes, now.plus(PDF_KEPT_FOR)));
        }
        return new PdfFile(bytes, snapshot.poNumber() + " " + snapshot.supplier().name() + ".pdf");
    }

    private ExpectedDelivery find(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) {
            throw ExpectedDeliveryException.notFound();
        }
        ExpectedDelivery order = repository.findByShareToken(token).orElseThrow(ExpectedDeliveryException::notFound);
        if (order.getSnapshot() == null) {
            throw ExpectedDeliveryException.notFound();
        }
        return order;
    }

    private PurchaseOrderSnapshot snapshot(ExpectedDelivery order) {
        return jsonMapper.readValue(order.getSnapshot(), PurchaseOrderSnapshot.class);
    }

    /** At most {@value #LIMIT_PER_MINUTE} requests a minute from one address. */
    private void limit(String callerAddress) {
        String key = callerAddress == null ? "unknown" : callerAddress;
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        if (recent.size() > 10_000) {
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
            if (history.size() >= LIMIT_PER_MINUTE) {
                throw new ExpectedDeliveryException(
                        HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again in a minute.");
            }
            history.addLast(now);
        }
    }
}
