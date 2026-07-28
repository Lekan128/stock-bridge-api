package com.procurepal_services.stock_bridge_api.notification;

import com.procurepal_services.stock_bridge_api.notification.dto.NotificationResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bell's feed. No @PreAuthorize beyond being an authenticated tenant user:
 * notifications are addressed to you or to your company, and there is no coherent
 * permission for "may be told things". The visibility predicate in
 * NotificationRepository is the access control.
 *
 * The unread badge count comes from {@code ?unreadOnly=true}'s {@code totalElements}
 * rather than from a bespoke field on the page, so the response keeps the standard
 * Spring {@code Page} shape the rest of the API uses.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Page<NotificationResponse> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return notificationService.list(principal.getUserId(), unreadOnly, pageable);
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return notificationService.markRead(id, principal.getUserId());
    }

    /** Returns the number actually flipped, so an optimistic UI can reconcile its badge in one step. */
    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return Map.of("markedRead", notificationService.markAllRead(principal.getUserId()));
    }
}
