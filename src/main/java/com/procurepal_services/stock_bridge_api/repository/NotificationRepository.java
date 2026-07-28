package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped. "Visible to me" is not the same as "addressed to me": a
 * notification with user_id NULL belongs to the whole company and must reach
 * everyone, so the visibility finders below take both the client and the user and
 * accept either. The plain client-wide finders exist for a company-level inbox
 * view where that distinction does not matter.
 */
public interface NotificationRepository extends TenantScopedRepository<Notification, UUID> {

    Page<Notification> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.clientId = :clientId "
            + "AND (n.userId IS NULL OR n.userId = :userId) ORDER BY n.createdAt DESC")
    Page<Notification> findVisibleTo(
            @Param("clientId") UUID clientId, @Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.clientId = :clientId "
            + "AND (n.userId IS NULL OR n.userId = :userId) AND n.readAt IS NULL ORDER BY n.createdAt DESC")
    Page<Notification> findUnreadVisibleTo(
            @Param("clientId") UUID clientId, @Param("userId") UUID userId, Pageable pageable);

    /** What the header bell polls. Indexed by (client_id, read_at) so it never touches the heap. */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.clientId = :clientId "
            + "AND (n.userId IS NULL OR n.userId = :userId) AND n.readAt IS NULL")
    long countUnreadVisibleTo(@Param("clientId") UUID clientId, @Param("userId") UUID userId);

    /**
     * Bulk "mark all read". A single UPDATE rather than loading and saving each row:
     * an inbox with hundreds of unread rows should not become hundreds of statements,
     * and nothing about the entities is needed to set one timestamp.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.clientId = :clientId "
            + "AND (n.userId IS NULL OR n.userId = :userId) AND n.readAt IS NULL")
    int markAllReadFor(
            @Param("clientId") UUID clientId,
            @Param("userId") UUID userId,
            @Param("readAt") OffsetDateTime readAt);
}
