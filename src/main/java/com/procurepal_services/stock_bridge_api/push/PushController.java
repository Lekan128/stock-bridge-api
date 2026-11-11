package com.procurepal_services.stock_bridge_api.push;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * This device's notifications, for the person signed in. No id in any path, as with /api/me: the only
 * subscriptions these reach are the caller's own.
 */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {

    /** What the browser's PushSubscription.toJSON() gives. */
    public record SubscriptionRequest(@NotBlank @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys) {
        public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
        }
    }

    public record Unsubscribe(@NotBlank String endpoint) {
    }

    private final PushService pushService;

    @GetMapping("/key")
    public Map<String, String> key() {
        return Map.of("publicKey", pushService.publicKey());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Void> subscribe(
            @Valid @RequestBody SubscriptionRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (!request.endpoint().startsWith("https://")) {
            return ResponseEntity.badRequest().build();
        }
        pushService.subscribe(principal.getClientId(), principal.getUserId(), request.endpoint(), request.keys().p256dh(),
                request.keys().auth(), userAgent);
        return ResponseEntity.noContent().build();
    }

    /** A body on DELETE, as the endpoint is too long and too private for a URL. */
    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(
            @Valid @RequestBody Unsubscribe request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        pushService.unsubscribe(principal.getUserId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
