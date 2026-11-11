package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/public/price-requests/{token}} - the supplier's own page for typing their prices, from
 * the link in the buyer's message (PURCHASE_ORDER_PLAN.md section 11.3). No login: the unguessable
 * token is the whole access check, and it can only read and write that one supplier's reply.
 *
 * <p>HAZARD, as for every path in SecurityConfig's permit-all list: no principal, so the tenant filter
 * is off; the service looks up by token alone. Limited per address; replies stop once the buyer decides.
 */
@RestController
@RequestMapping("/api/public/price-requests")
@RequiredArgsConstructor
public class PublicPriceRequestController {

    private static final int LIMIT_PER_MINUTE = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final PriceRequestService service;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @GetMapping("/{token}")
    public ResponseEntity<PriceRequestService.PublicView> view(@PathVariable String token, HttpServletRequest request) {
        limit(request.getRemoteAddr());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(service.view(token));
    }

    /** Called by the page's script, so a WhatsApp link preview never counts as "opened". */
    @PostMapping("/{token}/opened")
    public ResponseEntity<Void> opened(@PathVariable String token, HttpServletRequest request) {
        limit(request.getRemoteAddr());
        service.opened(token);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{token}/reply")
    public PriceRequestService.PublicView reply(
            @PathVariable String token, @RequestBody PriceRequestService.Reply reply, HttpServletRequest request) {
        limit(request.getRemoteAddr());
        return service.reply(token, reply);
    }

    @ExceptionHandler(ExpectedDeliveryException.class)
    public ResponseEntity<ApiError> handle(ExpectedDeliveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }

    private void limit(String callerAddress) {
        String key = callerAddress == null ? "unknown" : callerAddress;
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        if (recent.size() > 10_000) {
            recent.clear();
        }
        Deque<Instant> history = recent.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
                history.pollFirst();
            }
            if (history.size() >= LIMIT_PER_MINUTE) {
                throw new ExpectedDeliveryException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again in a minute.");
            }
            history.addLast(now);
        }
    }
}
