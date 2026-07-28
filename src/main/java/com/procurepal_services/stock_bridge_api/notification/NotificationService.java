package com.procurepal_services.stock_bridge_api.notification;

import com.procurepal_services.stock_bridge_api.entity.Notification;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.notification.dto.NotificationResponse;
import com.procurepal_services.stock_bridge_api.repository.NotificationRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications, polled by the header bell.
 *
 * <h2>Writing across tenants</h2>
 * Notifications are tenant-scoped, and almost every one of them is written from a
 * request belonging to somebody else: a buyer places an order and ProcurePal must be
 * told; ProcurePal advances a status and the buyer must be told; a Monnify webhook
 * with no tenant context at all must tell both. Every write therefore goes through
 * {@link TenantScopeExecutor}, which moves TenantContext AND the Hibernate filter to
 * the recipient for the duration of the insert - TenantAwareEntity's @PrePersist
 * would otherwise refuse to persist, or stamp the wrong client_id.
 *
 * <h2>userId null</h2>
 * The common case. A new order concerns whoever is on shift, not one named person,
 * so it is addressed to the whole company and every user of that company sees it.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final TenantScopeExecutor tenantScopeExecutor;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, boolean unreadOnly, Pageable pageable) {
        UUID clientId = requireTenantId();
        Page<Notification> page = unreadOnly
                ? notificationRepository.findUnreadVisibleTo(clientId, userId, pageable)
                : notificationRepository.findVisibleTo(clientId, userId, pageable);
        return page.map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countUnreadVisibleTo(requireTenantId(), userId);
    }

    @Transactional
    public NotificationResponse markRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository
                .findByIdForCurrentTenant(notificationId)
                .filter(candidate -> candidate.getUserId() == null || candidate.getUserId().equals(userId))
                .orElseThrow(NotificationNotFoundException::new);
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
        }
        return NotificationResponse.from(notification);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllReadFor(requireTenantId(), userId, OffsetDateTime.now());
    }

    /**
     * The single write path. Deliberately swallows nothing and returns nothing: a
     * notification is a side effect of the caller's business transaction and shares
     * it, so if the order rolls back the notification does too. Telling someone about
     * an order that does not exist is worse than not telling them.
     */
    @Transactional
    public void notifyClient(
            UUID clientId,
            UUID userId,
            NotificationType type,
            String title,
            String body,
            String link,
            UUID orderId) {
        if (clientId == null) {
            return;
        }
        tenantScopeExecutor.runAs(clientId, () -> notificationRepository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(truncate(body, 1000))
                .link(truncate(link, 300))
                .orderId(orderId)
                .build()));
    }

    /** Convenience for the order flows, which always link to an order and address the whole company. */
    @Transactional
    public void notifyAboutOrder(
            UUID clientId, NotificationType type, String title, String body, String link, Order order) {
        notifyClient(clientId, null, type, title, body, link, order.getId());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
