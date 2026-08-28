package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads over the rows of one import escrow.
 *
 * <h2>Why this is a plain JpaRepository and every finder names a session</h2>
 * {@code ImportSessionRow} is not a {@code TenantAwareEntity} - it has no {@code client_id} and
 * is reached only through its session, the same non-tenant-scoped-child pattern {@code
 * StockMovementAllocationRepository} follows. So there is deliberately <b>no finder here that
 * takes a row id alone</b>: {@link #findByIdAndSessionId} exists instead, and the caller is
 * expected to have loaded the session through {@code ImportSessionRepository}'s tenant-scoped
 * methods first. That is not ceremony - a row is uninterpretable without its session's kind,
 * mode and column mapping, so every correct caller is already holding it, and requiring the id
 * here is what turns "the caller ought to check tenancy" into "the caller cannot avoid having
 * checked tenancy".
 */
public interface ImportSessionRowRepository extends JpaRepository<ImportSessionRow, UUID> {

    /**
     * The review grid's unfiltered page ("All"), in the order the user's own spreadsheet has
     * them. Excel order rather than insertion or status order, because the number in the first
     * column is the number they will look for in their file.
     */
    Page<ImportSessionRow> findAllBySessionIdOrderByExcelRowAsc(UUID sessionId, Pageable pageable);

    /**
     * The review grid's filtered page, and the reason "Issues" can be the default view when
     * errors exist (design doc 9.3) - nobody scrolls 300 rows looking for red. Backed by {@code
     * idx_import_session_rows_session_id_status}, which carries {@code excel_row} as its third
     * column so this ordering is read straight off the index rather than sorted.
     */
    Page<ImportSessionRow> findAllBySessionIdAndStatusOrderByExcelRowAsc(
            UUID sessionId, ImportRowStatus status, Pageable pageable);

    /**
     * Every row of a session, Excel order, unpaged. For the commit, the preview and the batch
     * validation pass - all three of which are defined over the whole file by construction
     * (cross-row rules like duplicate-SKU-within-file and the section 7.1 continuation-row
     * convention cannot be decided a page at a time). Bounded by the 5,000-row cap the upload
     * enforces (contract section 6, {@code MAX_ROWS}), which is the reason that cap exists.
     */
    List<ImportSessionRow> findAllBySessionIdOrderByExcelRowAsc(UUID sessionId);

    /**
     * Rows in a given set of statuses, Excel order - the commit's own read, which wants the
     * committable ones ({@code VALID} and {@code WARNING}: a warning is something worth saying,
     * not something blocking) without loading the skipped and errored ones it will not write.
     */
    List<ImportSessionRow> findAllBySessionIdAndStatusInOrderByExcelRowAsc(
            UUID sessionId, Collection<ImportRowStatus> statuses);

    /**
     * One row, scoped by its session - see the class javadoc for why there is no by-id-alone
     * finder. Backs {@code PATCH /api/imports/{id}/rows/{rowId}} and its {@code /skip} sibling,
     * both of which carry the session id in the path precisely so this check is possible.
     */
    /**
     * Paged form of the status filter, added by M4 for {@code GET /rows?status=ISSUES}.
     *
     * <p>BULK_IMPORT_CONTRACT.md section 3 makes ISSUES a server-side pseudo-filter meaning
     * ERROR+WARNING in ONE correctly-paged response, and is explicit that the frontend must not
     * merge two requests to fake it. Two requests cannot be paged coherently - page 2 of errors
     * and page 2 of warnings are not page 2 of anything - and the review screen defaults to this
     * view, so a warning that fell off the seam between them would be exactly the silent drop
     * section 8.8 forbids. Hence a real IN-list query rather than two calls.
     */
    Page<ImportSessionRow> findAllBySessionIdAndStatusInOrderByExcelRowAsc(
            UUID sessionId, Collection<ImportRowStatus> statuses, Pageable pageable);

    long countBySessionIdAndStatusIn(UUID sessionId, Collection<ImportRowStatus> statuses);

    Optional<ImportSessionRow> findByIdAndSessionId(UUID id, UUID sessionId);

    /**
     * Counts per status for one session, so {@code ImportSession}'s five cached counters can be
     * recomputed <b>without loading a single row</b>. That matters more than it looks: the
     * review grid issues one {@code PATCH .../rows/{rowId}} per keystroke-settle and the
     * contract requires each to return the revalidated row rather than 204, so the grid never
     * refetches - which means the counters in the header have to be refreshed on the same
     * request, several times a second, over a file that may have 5,000 rows. A GROUP BY over a
     * covering index is the version of that which stays cheap; {@code
     * findAllBySessionIdOrderByExcelRowAsc(...).stream().collect(groupingBy(...))} is the
     * version that quietly makes the review screen unusable at the row cap it advertises.
     *
     * <p>Each row: {@code [status(ImportRowStatus), count(long)]}. {@code Object[]} rather than
     * a projection type, matching the convention {@code StockMovementRepository}'s analytics
     * queries already established in this package.
     */
    @Query("SELECT r.status, COUNT(r) FROM ImportSessionRow r WHERE r.session.id = :sessionId GROUP BY r.status")
    List<Object[]> countByStatus(@Param("sessionId") UUID sessionId);

    /**
     * Every distinct raw value that appeared in one column across a session's rows, with how
     * many rows carried it - the input to design doc 6.4's distinct-value resolution ("'Dangote
     * Ltd' appears on 47 rows and matches no CompanyVendor") and to the {@code bulkFixCount}
     * that powers {@code [Fix all 12 "KGS" rows]}, which contract section 4 requires to be
     * non-null on every error that has one so the frontend never computes it.
     *
     * <p>Native rather than JPQL because it reaches inside {@code raw}: {@code ->>} is Postgres
     * jsonb text extraction and has no JPQL spelling. Safe with a bound {@code column}
     * parameter - {@code ->>} takes the key as a value, not as identifier syntax, so a column
     * name never becomes part of the statement text. Same "Postgres-specific, and this is a
     * Postgres-only app" reasoning {@code StockMovementRepository.movementsOverTime} states.
     *
     * <p>{@code GROUP BY}/{@code ORDER BY} reference the {@code value} output-column ALIAS
     * rather than repeating {@code r.raw ->> :column}, and that is load-bearing rather than
     * tidy: Postgres requires the GROUP BY expression to be syntactically identical to the one
     * in the SELECT list, and a bound parameter does not satisfy that test - repeating the
     * expression fails with "column r.raw must appear in the GROUP BY clause", which is a
     * confusing way to say "your two placeholders are not the same placeholder". Grouping on
     * the alias sidesteps it entirely. {@code movementsOverTime} hit the same wall for the same
     * reason and states it in its own javadoc; this is that note applied to a second query.
     *
     * <p>Each row: {@code [value(text), rowCount(bigint)]}. Rows whose raw value for the column
     * is absent or JSON null are excluded - there is no unresolved VALUE to ask a human about
     * when the cell was empty; an empty required cell is an ordinary row error instead.
     */
    @Query(
            value = "SELECT r.raw ->> :column AS value, COUNT(*) AS row_count "
                    + "FROM import_session_rows r "
                    + "WHERE r.session_id = :sessionId AND r.raw ->> :column IS NOT NULL "
                    + "GROUP BY value "
                    + "ORDER BY row_count DESC, value ASC",
            nativeQuery = true)
    List<Object[]> countDistinctRawValues(@Param("sessionId") UUID sessionId, @Param("column") String column);

    /**
     * Bulk delete of one session's rows. Present for the re-parse path (a changed column mapping
     * that has to rebuild every row) rather than for expiry - the expiry job deletes the SESSION
     * and the schema's {@code ON DELETE CASCADE} takes the rows with it, which is both faster
     * and impossible to get half-right.
     *
     * <p>{@code @Modifying(clearAutomatically = true)} because a bulk delete goes straight to the
     * database and leaves any already-loaded rows in the persistence context pointing at rows
     * that no longer exist - the classic stale-first-level-cache trap. Flushing first would not
     * help; clearing after is what makes a subsequent read in the same transaction honest.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ImportSessionRow r WHERE r.session.id = :sessionId")
    int deleteAllBySessionId(@Param("sessionId") UUID sessionId);
}
