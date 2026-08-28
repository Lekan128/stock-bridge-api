package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
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
 * One uploaded spreadsheet, from parse through in-browser review to commit - the escrow of
 * BULK_IMPORT_DESIGN.md section 6.1. Extends {@link TenantAwareEntity} like every other
 * row-level entity here; an import is one company's private work in progress and must never be
 * reachable from another's, including by a guessed session id in the
 * {@code /app/products/import/:sessionId} URL the review screen is deliberately linkable at.
 *
 * <h2>Why this is a table at all, and not a parse held in memory for one request</h2>
 * Every other upload in this schema is a request: bytes arrive, they are validated, they either
 * land or they come back as a list of row errors. {@code ProductManagementService.bulkUpload}
 * is exactly that shape, and its own javadoc names the tradeoff it made. What it could not name
 * at the time is the cost: the only repair loop that shape can offer is "go back to Excel and
 * try again", so a user who mistyped four cells out of three hundred pays a full round trip
 * through a spreadsheet editor to fix them, after they thought they were finished. Design doc
 * section 5.1 calls that "the single most demoralizing place to put it", and it is the reason
 * this whole feature exists.
 *
 * <p>The replacement is a review step, and <b>a review step is not a request - it is a
 * conversation</b>. The user fixes one row. They go and ask a colleague which supplier "Ade &amp;
 * Sons" actually is. They close the tab. They come back tomorrow. They refresh. They send the
 * link to whoever has the invoice. Not one of those survives a parse held for the length of one
 * HTTP call, and all of them are free once the parse is two ordinary tables - which is why
 * "Save &amp; finish later" on the review screen is a real button rather than a promise the
 * backend cannot keep.
 *
 * <p>Persisting it buys three more things that would each otherwise need their own mechanism:
 * "who imported what, when" for the recent-imports list; the {@code raw}/{@code normalized}
 * pair on {@link ImportSessionRow} that keeps a repair inspectable against what the file
 * actually said; and the undo of design doc section 6.6, which needs a durable batch identity
 * to stamp onto everything a commit wrote (see {@code StockMovement.importBatchId} and
 * {@code Product.importBatchId}).
 *
 * <h2>The counters are cached, not authoritative</h2>
 * {@link #rowCount} and its four siblings exist so the review header ("38 ready, 4 need
 * attention, 2 skipped") renders without scanning {@code import_session_rows}. All five are
 * derived and recomputable at any time from {@code ImportSessionRowRepository.countByStatus} -
 * the same cached-not-authoritative status {@code Product.quantityOnHand} and {@code
 * ProductVendor.quantityOnHandFromVendor} already carry, and the same rule applies: if a
 * counter and the rows ever disagree, the rows are right.
 *
 * <h2>The JSON columns</h2>
 * {@link #columnMapping} and {@link #valueMappings} are mapped with Hibernate 6's
 * {@code @JdbcTypeCode(SqlTypes.JSON)} onto {@code jsonb}, the same annotation {@code Payment.
 * providerPayload} and {@code PaymentWebhookEvent.payload} already use - the difference being
 * that those two keep a provider's opaque bytes as a {@code String} on purpose, whereas these
 * are our own documents that the service layer reads key by key, so they are mapped as maps.
 * No new dependency: Hibernate serializes them through the Jackson {@code ObjectMapper} Spring
 * Boot already configures.
 */
@Entity
@Table(name = "import_sessions")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ImportSession extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Which row handler commits this file - and only that. Fixed at upload and never changed:
     * every row in the session was parsed against this kind's field set, so a session that
     * switched kind mid-life would be describing a file nobody uploaded. See {@link ImportKind}
     * for why the two kinds are separate imports rather than one import with a flag.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 30)
    private ImportKind kind;

    /**
     * The duplicate-SKU decision, made once at upload because it changes what counts as an
     * error during validation and therefore cannot be asked afterwards - see {@link ImportMode}.
     * Unlike {@link #kind} this is not {@code updatable = false}: changing it is a legitimate,
     * if uncommon, thing to do from the review screen ("actually, do update the ones that
     * already exist"), and the answer is simply to revalidate every row against the new
     * rulebook, which the engine can already do because {@code PATCH .../mapping} needs the
     * same capability.
     *
     * <p>Always {@link ImportMode#CREATE_ONLY} for a {@link ImportKind#STOCK_IN} session, where
     * it means nothing and is ignored (BULK_IMPORT_CONTRACT.md section 1).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private ImportMode mode;

    /**
     * See {@link ImportStatus} - this field is also the frontend's router, and the {@code READY
     * -> COMMITTING} transition taken under a row lock is the guard that stops a double-clicked
     * Commit from importing everything twice.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ImportStatus status;

    /**
     * What the user called the file. Shown in the recent-imports list and in every piece of copy
     * that names the import ("Import 42 rows from products-jan.xlsx"). Never used to locate
     * anything - the uploaded bytes are not kept, only the parse.
     */
    @Column(name = "original_filename", nullable = false, updatable = false, length = 255)
    private String originalFilename;

    /**
     * Resolved "their spreadsheet header -&gt; our field key" map (design doc 6.2). An IDENTITY
     * map in the common case, because field keys are snake_case and identical to our own
     * template's column headers (BULK_IMPORT_CONTRACT.md section 5) - which is precisely what
     * lets the mapping SCREEN be skipped for anyone importing the template we generated. Only
     * an unmapped REQUIRED field forces that screen to appear at all.
     *
     * <p>A null value against a header means "this column is deliberately ignored", which is a
     * different answer from the header being absent from the map, so callers must distinguish
     * {@code containsKey} from a null get.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping")
    private Map<String, String> columnMapping;

    /**
     * Accepted answers to the distinct-value questions of design doc 6.4: which {@code
     * CompanyVendor} "Dangote Ltd" is, which {@code UnitOfMeasure} "KGS" meant, which product an
     * unmatched SKU should resolve to. Keyed by column and then by the raw value that appeared
     * in the file, so one decision fixes every row that used it - resolution is per distinct
     * VALUE, never per row, and that is the difference between a 30-second repair and a
     * 10-minute one.
     *
     * <p>Deliberately untyped at this level ({@code Map<String, Object>}) rather than a mapped
     * structure: the accepted resolution is one of five differently-shaped things (contract
     * section 3's {@code EXISTING | CREATE_NEW | LITERAL | BLANK | SKIP_ROWS}), the set is
     * expected to grow, and the entity layer has no business being the place a new resolution
     * kind has to be added. The session engine owns that vocabulary.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_mappings")
    private Map<String, Object> valueMappings;

    /** Total rows parsed from the file, example rows and skipped rows included. Cached - see the class javadoc. */
    @Column(name = "row_count", nullable = false)
    private int rowCount;

    /** Rows that will be written by the commit. Cached - see the class javadoc. */
    @Column(name = "valid_count", nullable = false)
    private int validCount;

    /** Rows blocking the commit until fixed or skipped. Cached - see the class javadoc. */
    @Column(name = "error_count", nullable = false)
    private int errorCount;

    /** Rows that will be written but have something worth saying first. Cached - see the class javadoc. */
    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    /** Rows excluded by decision rather than by failure. Cached - see the class javadoc. */
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /**
     * Who uploaded it. Nullable and {@code ON DELETE SET NULL} in the schema, matching {@code
     * StockMovement.createdBy}'s own reasoning: the import is a historical fact that must
     * outlive the person who ran it leaving the company, and losing the session because a user
     * row went away would take its committed {@code import_batch_id} links with it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", updatable = false)
    private User uploadedBy;

    /**
     * Null until the commit succeeds. Together with {@code status == COMMITTED} this is what the
     * recent-imports list reads to decide whether to offer [Undo].
     */
    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    /**
     * 48 hours out (contract section 6, {@code SESSION_TTL_HOURS}). A scheduled job deletes
     * expired UNCOMMITTED sessions - see {@code ImportSessionRepository.findExpiredUncommitted}.
     * A COMMITTED session deliberately outlives this: it is the undo record and the target of
     * every {@code import_batch_id}, not a work in progress.
     */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Whether this session may still be discarded or purged - i.e. it never wrote anything. The
     * expiry job's predicate expressed once, here, rather than as a status list repeated at each
     * call site: a session that committed owns rows in {@code stock_movements} and {@code
     * products} that point back at it (both {@code ON DELETE RESTRICT}), so deleting it is not
     * merely undesirable, it is refused by the database.
     */
    public boolean isUncommitted() {
        return status != ImportStatus.COMMITTED && status != ImportStatus.COMMITTING;
    }
}
