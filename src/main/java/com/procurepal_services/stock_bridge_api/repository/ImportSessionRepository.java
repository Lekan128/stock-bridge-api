package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads over the import escrow. Extends {@link TenantScopedRepository} like every other
 * repository over a {@code TenantAwareEntity} here, which is what gives the session engine
 * {@code findByIdForCurrentTenant} for free - the check that stops a guessed session id in the
 * linkable {@code /app/products/import/:sessionId} URL from reaching another company's import.
 */
public interface ImportSessionRepository extends TenantScopedRepository<ImportSession, UUID> {

    /**
     * The recent-imports list on the upload screen (design doc 9.2), newest first. This is the
     * only way a session is ever reached without already knowing its id, which is why {@code
     * idx_import_sessions_client_id_created_at} exists in exactly this shape.
     */
    Page<ImportSession> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    /** Same list, filtered to one kind - {@code GET /api/imports?kind=}. */
    Page<ImportSession> findAllByClientIdAndKindOrderByCreatedAtDesc(UUID clientId, ImportKind kind, Pageable pageable);

    /**
     * The expiry job's whole read: uncommitted sessions past their 48h TTL (design doc section
     * 11, contract {@code SESSION_TTL_HOURS}). Not tenant-scoped, deliberately and unlike every
     * other finder on this interface - the job runs on a schedule with no {@code TenantContext}
     * at all and must sweep the whole table, which is exactly why it takes no {@code clientId}
     * and why that omission is stated here rather than left to look like an oversight.
     *
     * <p>The status set is passed in rather than hardcoded so the caller states which lifecycle
     * stages it considers collectable; {@code COMMITTED} must never be among them, and neither
     * should {@code COMMITTING}, because a session in either state owns {@code stock_movements}
     * and {@code products} rows whose {@code import_batch_id} points back at it under {@code ON
     * DELETE RESTRICT} - deleting one is not merely undesirable, the database refuses it. See
     * {@code ImportSession.isUncommitted()}, which is that rule expressed once.
     *
     * <p>Paged rather than {@code List}: a purge that has fallen behind (a job that was off for
     * a week, a tenant that uploaded and abandoned a thousand files) must not load every
     * candidate into one heap at once. Callers are expected to loop until the page comes back
     * empty.
     */
    @Query("SELECT s FROM ImportSession s WHERE s.expiresAt < :asOf AND s.status IN :collectableStatuses "
            + "ORDER BY s.expiresAt ASC")
    Page<ImportSession> findExpiredUncommitted(
            @Param("asOf") OffsetDateTime asOf,
            @Param("collectableStatuses") Collection<ImportStatus> collectableStatuses,
            Pageable pageable);

    /**
     * Row-locks the session for the duration of the caller's transaction. This is the
     * idempotency guard of BULK_IMPORT_DESIGN.md section 11, and it is not optional: a
     * double-clicked Commit sends two requests, both read {@code status = READY}, and both
     * import the file unless something serializes them. The commit path must load the session
     * through THIS method, re-check that the status is still {@code READY}, and flip it to
     * {@code COMMITTING} inside the same transaction - so the second request blocks here, is
     * granted the lock only after the first commits, and then finds a status that is no longer
     * {@code READY} and answers 409 with the first call's result.
     *
     * <p>Same mechanism, same reasoning and same tradeoff as {@code
     * ProductRepository.findByIdAndClientIdForUpdate} (a pessimistic lock over a conditional
     * UPDATE, because the row has to be loaded as a managed entity anyway). Note that the lock
     * is what makes the status check meaningful - checking {@code status == READY} on an
     * unlocked read proves nothing, since the other request may be between its own check and
     * its own write at that moment.
     *
     * <p>Scoped by {@code clientId} as well as {@code id}, like every other tenant-scoped
     * finder: a lock granted before the tenancy check would let a caller from another company
     * block a stranger's commit, which is a denial of service even though it never reads a row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ImportSession s WHERE s.id = :id AND s.clientId = :clientId")
    Optional<ImportSession> findByIdAndClientIdForUpdate(@Param("id") UUID id, @Param("clientId") UUID clientId);

    /**
     * Every session a given set of ids resolves to within one tenant - the batched lookup behind
     * the recent-imports list's per-row undo affordance and the {@code ?importBatchId=} product
     * filter, so neither has to issue one query per session.
     */
    List<ImportSession> findAllByClientIdAndIdIn(UUID clientId, Collection<UUID> ids);
}
