package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An in-app notification, polled by the header bell. Tenant-scoped: ProcurePal's
 * "new order from Demo Retail Co" and the buyer's "your order is out for
 * delivery" are two rows belonging to two different clients.
 *
 * userId null means "the whole company", which is the common case - a new order
 * concerns whoever is on shift, not one named person. It is a raw UUID rather
 * than an association because notifications are frequently listed in bulk and the
 * user is never rendered; there is nothing to navigate to.
 *
 * orderId is a raw UUID for a stronger reason: on ProcurePal's copy of a
 * notification, clientId is ProcurePal while orderId points at the BUYER's order.
 * A mapped association there would be filtered by tenant and resolve to nothing.
 *
 * readAt is a timestamp rather than a boolean because "when did they see it" is
 * strictly more useful and costs nothing. No updated_at column: a notification is
 * created once and the only mutation is marking it read.
 *
 * TODO(future): this only writes an in-app notification that the bell polls for.
 * When ProcurePal has dispatch riders, a new order should also push to ProcurePal
 * staff and to the assigned rider (FCM/SMS/WhatsApp) rather than waiting for a
 * poll.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Notification extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false, updatable = false, length = 200)
    private String title;

    @Column(length = 1000, updatable = false)
    private String body;

    /**
     * In-app route, e.g. '/app/marketplace/orders/&lt;id&gt;'. Stored rather than
     * derived from type+orderId so reorganising routes for new notifications
     * doesn't break the links in old ones.
     */
    @Column(length = 300, updatable = false)
    private String link;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isRead() {
        return readAt != null;
    }
}
