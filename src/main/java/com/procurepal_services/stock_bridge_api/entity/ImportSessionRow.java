package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One spreadsheet row of an {@link ImportSession}, from parse through in-browser repair to
 * commit. See BULK_IMPORT_DESIGN.md section 6.1.
 *
 * <h2>raw and normalized are two columns on purpose</h2>
 * {@link #raw} is exactly what the cells contained and is <b>never rewritten</b>. Every repair -
 * a cell edited in the review grid, a value mapping applied across 47 rows, a re-run of the
 * column mapping - writes to {@link #normalized} instead. Keeping both is what makes a fix
 * inspectable against what the file actually said, which matters in three places that would
 * each otherwise need their own snapshot: the review grid, which shows the user the original
 * value beside its correction; the report (an .xlsx of every row with its outcome, the thing an
 * accountant asks for three weeks later); and the undo of design doc 6.6, whose catalog half
 * reverts an updated product's fields to the pre-update snapshot and has nowhere else to read
 * that from.
 *
 * <p>It also means a repair is never destructive. A user who fixes a cell wrongly has not lost
 * anything - the file's own answer is still sitting next to it - which is the property that
 * makes editing in the browser safe enough to be the primary repair loop instead of a
 * convenience over re-uploading.
 *
 * <h2>Not a TenantAwareEntity</h2>
 * No {@code clientId}. Reached only through {@link #session}, itself a tenant-scoped {@link
 * ImportSession} - the same non-tenant-scoped-child pattern {@link StockMovementAllocation} and
 * {@link ProductVendorPriceTier} already follow. Here the reasoning is stronger than tenancy: a
 * row is <b>uninterpretable</b> without its session's {@code kind}, {@code mode} and {@code
 * columnMapping} to read it against, so any correct read already holds the session and can
 * scope on its {@code clientId}. A {@code clientId} here would create a second place for that
 * answer to live and a second way for it to be wrong. {@code ImportSessionRowRepository}'s
 * finders all take a {@code sessionId} for exactly this reason - there is deliberately no
 * "find row by id" that does not name the session it belongs to.
 *
 * <h2>The JSON columns</h2>
 * Mapped with Hibernate 6's {@code @JdbcTypeCode(SqlTypes.JSON)} onto {@code jsonb}, the same
 * annotation {@code Payment.providerPayload} already uses. No new dependency - Hibernate
 * serializes them through the Jackson {@code ObjectMapper} Spring Boot already configures.
 */
@Entity
@Table(name = "import_session_rows")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ImportSessionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The escrow this row belongs to, and the only route by which it is ever reached - see the
     * class javadoc. LAZY: the row-paging query for the review grid already knows the session
     * (it filtered on it), so eagerly re-loading it per row would be an N+1 for an answer the
     * caller is holding.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ImportSession session;

    /**
     * 1-based, matching what the user sees in the spreadsheet - the convention {@code
     * ProductRowError} already established, and what every error message in this feature points
     * at ("Row 4"). Not an index into any array we hold: rows can be skipped, filtered and
     * paged, and this number stays the one the user can find in their own file. Unique per
     * session in the schema, which is what makes a re-parse idempotent rather than doubling the
     * file.
     */
    @Column(name = "excel_row", nullable = false, updatable = false)
    private int excelRow;

    /**
     * Exactly what the cells contained, keyed by the field key the column mapped to. Never
     * rewritten - see the class javadoc for the three things that depend on that.
     *
     * <p>{@code updatable = false} states it at the mapping level rather than trusting every
     * future caller to remember, the same way {@link StockMovement}'s columns do: Hibernate will
     * not emit an UPDATE for this column even if somebody calls the setter, so a repair that
     * meant to touch {@link #normalized} and touched this instead fails quietly-correctly
     * (nothing changes) rather than silently destroying the original.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", updatable = false)
    private Map<String, Object> raw;

    /**
     * Post-parse, post-repair values - what the commit actually reads. Typed values, not cell
     * strings: a quantity is a number here, a unit is a resolved {@code UnitOfMeasure} CODE
     * (never the display label or the casing the user typed), a vendor is a resolved id.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized")
    private Map<String, Object> normalized;

    /** See {@link ImportRowStatus}. Also the filter behind {@code GET /api/imports/{id}/rows?status=}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ImportRowStatus status;

    /**
     * {@code [{column, message}, ...]} - the shape of {@code ProductRowError} minus its row
     * number, which is {@link #excelRow}. A document rather than a child table because it is
     * read and written whole, exactly once per row revalidation, and is never queried across
     * rows by its contents.
     *
     * <p>Left as {@code List<Map<String, Object>>} rather than a mapped record deliberately.
     * The wire shape the review grid renders (BULK_IMPORT_CONTRACT.md section 4) carries more
     * than {@code column} and {@code message} - a {@code suggestion} ("Did you mean Kilogram
     * (kg)?") and a {@code bulkFixCount} that powers {@code [Fix all 12 "KGS" rows]}, the single
     * highest-value interaction on the review screen - and that vocabulary belongs to the
     * session engine, which composes the copy, not to the entity layer, which only has to store
     * it whole and hand it back unchanged.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors")
    private List<Map<String, Object>> errors;

    /**
     * The product this row matched (stock-in) or will update (catalog). A raw UUID with no
     * mapped association and no FK, for two reasons that both hold: the referent depends on the
     * session's {@link ImportKind}, so no single target is correct; and a resolution is still
     * being DECIDED while the session is open - the user can pick a different match in the
     * review grid, or "create this product" instead - so a constraint here would enforce a
     * relationship that is not settled yet. Same raw-UUID reasoning {@code
     * Product.sourceProductId} and {@code Product.reviewedBy} already state for their own
     * out-of-band referents.
     */
    @Column(name = "resolved_entity_id")
    private UUID resolvedEntityId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Unlike {@link StockMovement}, this table DOES carry an {@code updatedAt} - a session row
     * is the one thing in this feature that is meant to be edited, repeatedly, by a human, for
     * up to 48 hours. That is the whole point of it.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Whether the commit will write this row. Stated once, here, rather than as a status
     * comparison repeated in the preview, the commit and the counters - three places that must
     * agree, and the kind of thing that drifts when it is spelled out three times. {@link
     * ImportRowStatus#WARNING} counts as committable by design: a warning is something worth
     * saying, not something blocking (design doc 6.3's ignored-quantity notice is the archetype).
     */
    public boolean isCommittable() {
        return status == ImportRowStatus.VALID || status == ImportRowStatus.WARNING;
    }
}
