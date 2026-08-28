-- Bulk product import and bulk stock-in: the persisted escrow that turns an
-- uploaded spreadsheet from a single request into a conversation the user can
-- leave and come back to, plus the two ledger corrections that feature cannot
-- honestly ship without. See BULK_IMPORT_DESIGN.md sections 3, 6.1, 6.5 and 8.4
-- for the full reasoning; this file states the SQL-level slice of it.
--
-- ============================================================================
-- WHY THE PARSE IS PERSISTED RATHER THAN HELD IN MEMORY
-- ============================================================================
-- Every other upload in this schema is a request: bytes arrive, they are
-- validated, they either land or they are rejected with a list of row errors
-- (products/bulk, ProductManagementService.bulkUpload - "V1: creates only,
-- all-or-nothing"). That shape is exactly what made the existing bulk upload
-- painful, and the design doc's section 5.1 names why: the only repair loop it
-- offers is "go back to Excel and try again", so a user who mistyped four cells
-- out of three hundred pays a full round trip through a spreadsheet editor to
-- fix them.
--
-- The replacement is a REVIEW STEP, and a review step is not a request. The user
-- fixes one row, goes to ask a colleague which supplier "Ade & Sons" actually
-- is, closes the tab, comes back tomorrow, refreshes, shares the link with
-- somebody who has the invoice. None of that survives an in-memory parse held
-- for the length of one HTTP call, and all of it is free once the parse is two
-- ordinary tables. It also buys three things that would otherwise each need
-- their own mechanism: "who imported what, when" for the recent-imports list,
-- the immutable raw-vs-normalized pair that makes a repair inspectable against
-- what the file actually said, and - via import_batch_id further down - the undo
-- in design doc section 6.6.
--
-- ============================================================================
-- WHY THIS IS ONE FILE DOING THREE THINGS, IN THIS ORDER
-- ============================================================================
--   1. CREATE import_sessions / import_session_rows - the escrow itself, so
--      there is somewhere for import_batch_id to POINT before it is added.
--   2. ALTER stock_movements ADD occurred_at - backdating support for the FIFO
--      order (design doc section 8.4), created, backfilled and constrained
--      together because a NOT NULL column cannot be added to a populated table
--      in one statement without inventing a value for every existing row.
--   3. ALTER stock_movements / products ADD import_batch_id - the stamp that
--      makes "show me what this import created" and the section 6.6 undo
--      possible, added last because its FK target is created in step 1.
--
-- Same out-of-order-safe convention V11/V19 state in full: every statement here
-- is written to be correct whether Flyway runs this in a fresh V1..V20 sequence
-- or "out of order" behind an already-seeded local database - the backfill in
-- step 2 is expressed as "whatever rows exist right now", not a fixed set.
--
-- ============================================================================
-- ON THE APPEND-ONLY LEDGER, AND WHY STEP 2's BACKFILL IS SAFE
-- ============================================================================
-- stock_movements is append-only by CONVENTION and by mapping - V1's own table
-- comment says so ("append-only audit log ... No updated_at - movements are
-- never edited, only recorded"), and every column on the StockMovement entity is
-- updatable = false so Hibernate cannot emit an UPDATE against one. It is NOT
-- guarded by a database trigger: vendor_ledger_entries_append_only() (V14, fixed
-- in V16) is attached to vendor_ledger_entries and
-- vendor_settlement_settings_changes only - the money-history tables - and has
-- never covered this one. There is therefore no allow-list to add occurred_at
-- to, and no SET LOCAL app.ledger_maintenance = 'on' needed around the backfill
-- below.
--
-- That was checked rather than assumed, and it is worth stating here because the
-- next person to backfill a column onto this table will ask the same question.
-- If a trigger is ever added to stock_movements, read V16's header in full
-- FIRST: its whole subject is a maintenance hatch that made every permitted
-- UPDATE a silent no-op which still reported rows affected, which is precisely
-- the failure mode a migration like this one would not notice.
--
-- occurred_at being updatable = false on the entity is deliberate and stays that
-- way. A delivery date recorded wrongly is corrected the way every other ledger
-- mistake is: by posting a compensating movement, not by editing history.

-- ============================================================================
-- import_sessions: the escrow, one row per uploaded file. Design doc 6.1.
-- ============================================================================
CREATE TABLE import_sessions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Tenant-scoped like every other row-level entity here (products,
    -- stock_movements, product_vendors). An import is one company's private
    -- work-in-progress and must never be reachable from another's, including
    -- by a guessed session id in the /app/products/import/:sessionId URL the
    -- review screen is linkable at.
    client_id          UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- Which row handler commits this file - PRODUCT_CATALOG builds/updates the
    -- catalog, STOCK_IN records deliveries. Everything BEFORE the handler (file
    -- handling, column mapping, error surfacing, the review grid, value
    -- resolution, the confirmation summary, the report) is deliberately shared;
    -- see BULK_IMPORT_CONTRACT.md section 2, whose whole purpose is to make that
    -- sharing a type-system fact rather than a matter of discipline.
    kind               VARCHAR(30) NOT NULL,
    -- The duplicate-SKU decision, made once and up front rather than asked after
    -- the fact - it changes what "error" MEANS during validation, so it cannot be
    -- a question posed afterwards (design doc 6.3/9.2). Meaningless for a
    -- STOCK_IN import: persist CREATE_ONLY and ignore it, per contract section 1.
    mode               VARCHAR(30) NOT NULL,
    -- PARSING -> NEEDS_REVIEW | READY -> COMMITTING -> COMMITTED, with FAILED and
    -- EXPIRED as terminal exits. COMMITTING is not decoration: it is the
    -- idempotency guard in design doc section 11 - a double-clicked Commit
    -- transitions READY -> COMMITTING under a row lock (see
    -- ImportSessionRepository.findByIdAndClientIdForUpdate), and the second call
    -- finds a status that is no longer READY and answers 409 with the first
    -- call's result instead of importing everything twice.
    status             VARCHAR(30) NOT NULL,
    -- Shown in the recent-imports list, and in every piece of copy that names the
    -- import ("Import 42 rows from products-jan.xlsx"). Never used to locate the
    -- file - the bytes are not kept, only the parse.
    original_filename  VARCHAR(255) NOT NULL,
    -- Resolved "their header -> our field key" map (design doc 6.2). An IDENTITY
    -- map in the common case, because field keys are deliberately snake_case and
    -- identical to our own template's column headers (contract section 5), which
    -- is what lets the mapping SCREEN be skipped entirely for anyone using the
    -- template we generated - only an unmapped REQUIRED field forces it.
    column_mapping     JSONB,
    -- Accepted answers to "which vendor is 'Dangote Ltd'?", "what unit is 'KGS'?"
    -- - resolved once per DISTINCT value and applied across every row that used
    -- it, not once per row (design doc 6.4). One decision, 47 rows fixed; this is
    -- the column that makes that true.
    value_mappings     JSONB,
    -- The five summary counters, maintained on the session so the review
    -- screen's header ("38 ready, 4 need attention, 2 skipped") never scans
    -- import_session_rows. Design doc 6.1 names three; the wire shape the
    -- frontend renders (BULK_IMPORT_CONTRACT.md section 4,
    -- ImportSessionResponse) has five, and warning/skipped are counters of
    -- exactly the same kind, so they are stored the same way rather than being
    -- the two that force a scan. All five are DERIVED - recomputable at any time
    -- from a GROUP BY over this session's rows (see
    -- ImportSessionRowRepository.countByStatus), same cached-not-authoritative
    -- status as products.quantity_on_hand.
    row_count          INTEGER NOT NULL DEFAULT 0,
    valid_count        INTEGER NOT NULL DEFAULT 0,
    error_count        INTEGER NOT NULL DEFAULT 0,
    warning_count      INTEGER NOT NULL DEFAULT 0,
    skipped_count      INTEGER NOT NULL DEFAULT 0,
    -- Who uploaded it. SET NULL rather than CASCADE, matching
    -- stock_movements.created_by's own reasoning: the import is a historical fact
    -- that must survive the person who ran it leaving the company, and losing the
    -- whole session because a user row went away would take its committed
    -- import_batch_id links (below) with it.
    uploaded_by        UUID REFERENCES users (id) ON DELETE SET NULL,
    -- Null until the commit succeeds. Together with status = 'COMMITTED' this is
    -- what the recent-imports list reads to decide whether to offer [Undo].
    committed_at       TIMESTAMPTZ,
    -- TTL, 48h (contract section 6, SESSION_TTL_HOURS). A scheduled job deletes
    -- expired UNCOMMITTED sessions; a committed one is kept, because it is the
    -- undo record and the "what did this import create" link target, not a
    -- work-in-progress. The UI mentions this exactly once ("we'll keep this for 2
    -- days") - see design doc section 11.
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The three enums, spelled exactly as BULK_IMPORT_CONTRACT.md section 1
    -- freezes them. CHECKs rather than the service-layer-only validation
    -- products.unit_of_measure/packaging_unit deliberately settled for: those two
    -- draw from a catalog that is expected to GROW (a tenant can request a unit
    -- that is not on the list yet), whereas these three are a closed contract
    -- shared verbatim with the frontend and with six sibling modules. A value
    -- outside these lists is not a tenant's unusual unit, it is a bug or a
    -- drifted module, and it should fail at the write rather than surface as a
    -- session no screen knows how to render.
    CONSTRAINT chk_import_sessions_kind CHECK (kind IN ('PRODUCT_CATALOG', 'STOCK_IN')),
    CONSTRAINT chk_import_sessions_mode CHECK (mode IN ('CREATE_ONLY', 'CREATE_OR_UPDATE', 'UPDATE_ONLY')),
    CONSTRAINT chk_import_sessions_status CHECK (
        status IN ('PARSING', 'NEEDS_REVIEW', 'READY', 'COMMITTING', 'COMMITTED', 'FAILED', 'EXPIRED')),
    CONSTRAINT chk_import_sessions_counts_non_negative CHECK (
        row_count >= 0 AND valid_count >= 0 AND error_count >= 0
        AND warning_count >= 0 AND skipped_count >= 0)
);

-- The recent-imports list, which is the only way a session is ever reached
-- without already knowing its id: newest first, one tenant at a time. DESC is
-- stated on created_at rather than left to a reversed scan because it is a
-- covering ordering for exactly the query that exists.
CREATE INDEX idx_import_sessions_client_id_created_at ON import_sessions (client_id, created_at DESC);
-- The expiry job's own read: "every session past its TTL that never committed".
-- status leads because it is the more selective half in the population that
-- matters (committed sessions accumulate forever and are never candidates), and
-- because the job filters on a fixed SET of statuses, which an index can serve
-- as several range scans on a leading equality column.
CREATE INDEX idx_import_sessions_status_expires_at ON import_sessions (status, expires_at);

CREATE TRIGGER trg_import_sessions_set_updated_at
    BEFORE UPDATE ON import_sessions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE import_sessions IS
    'One uploaded spreadsheet, persisted from parse through review to commit - the escrow '
    'behind BULK_IMPORT_DESIGN.md section 6.1. Persisted rather than held in memory because '
    'the review step is a conversation, not a request: the user closes the tab and comes back, '
    'edits one row at a time, refreshes the browser, shares the link. It is also what makes '
    '"who imported what, when" free and what makes the section 6.6 undo possible at all.';

COMMENT ON COLUMN import_sessions.kind IS
    'PRODUCT_CATALOG | STOCK_IN - selects the row handler that commits this file, and nothing '
    'else. Every step before the handler is deliberately shared between the two; see '
    'BULK_IMPORT_CONTRACT.md section 2.';

COMMENT ON COLUMN import_sessions.mode IS
    'CREATE_ONLY | CREATE_OR_UPDATE | UPDATE_ONLY - the duplicate-SKU decision, chosen at '
    'upload because it changes what counts as an error during validation and therefore cannot '
    'be asked afterwards (design doc 6.3). Meaningless for STOCK_IN: persisted as CREATE_ONLY '
    'and ignored, per contract section 1.';

COMMENT ON COLUMN import_sessions.status IS
    'PARSING -> NEEDS_REVIEW | READY -> COMMITTING -> COMMITTED, with FAILED and EXPIRED as '
    'terminal exits. COMMITTING is the idempotency guard from design doc section 11 - a '
    'double-clicked Commit transitions READY -> COMMITTING under a row lock, and the second '
    'call sees a status that is no longer READY and answers 409 with the first call''s result.';

COMMENT ON COLUMN import_sessions.column_mapping IS
    'Resolved "their header -> our field key" map (design doc 6.2). An identity map in the '
    'common case, because field keys are snake_case and identical to our own template''s '
    'headers (contract section 5) - which is exactly what lets the mapping screen be skipped '
    'for anyone importing the template we generated.';

COMMENT ON COLUMN import_sessions.value_mappings IS
    'Accepted answers to the distinct-value questions of design doc 6.4 - which CompanyVendor '
    '"Dangote Ltd" is, which UnitOfMeasure "KGS" meant. Resolved once per distinct VALUE and '
    'applied to every row that used it, never once per row.';

COMMENT ON COLUMN import_sessions.row_count IS
    'Cached summary counter, with valid/error/warning/skipped_count - so the review header '
    'renders without scanning import_session_rows. All five are derived and recomputable from '
    'a GROUP BY over this session''s rows; same cached-not-authoritative status as '
    'products.quantity_on_hand.';

COMMENT ON COLUMN import_sessions.expires_at IS
    'TTL, 48h (contract section 6). A scheduled job deletes expired UNCOMMITTED sessions. A '
    'COMMITTED session is deliberately kept past this: it is the undo record and the target of '
    'every stock_movements.import_batch_id / products.import_batch_id below, not a '
    'work-in-progress.';

COMMENT ON COLUMN import_sessions.committed_at IS
    'Null until commit succeeds. With status = COMMITTED, this is what the recent-imports list '
    'reads to decide whether to offer [Undo].';

-- ============================================================================
-- import_session_rows: one row per spreadsheet row. Design doc 6.1.
-- ============================================================================
CREATE TABLE import_session_rows (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE: a row has no meaning once its session is gone, and the expiry job
    -- deletes whole sessions rather than walking their rows. This is the one FK
    -- in this file that is genuinely exercised, not a defensive default.
    session_id         UUID NOT NULL REFERENCES import_sessions (id) ON DELETE CASCADE,
    -- 1-based, matching what the user sees in Excel - the convention
    -- ProductRowError already established and which every error message in this
    -- feature depends on ("Row 4"). Not an index into any array we hold.
    excel_row          INTEGER NOT NULL,
    -- Exactly what the cells contained, and NEVER rewritten. A repair writes to
    -- normalized; raw stays as the file said it, so a fix is always inspectable
    -- against the original and the section 6.6 undo has a pre-update snapshot to
    -- revert an updated product's fields to.
    raw                JSONB,
    -- Post-parse, post-repair. This is what commit actually reads. The pair is the
    -- point: two columns rather than one mutated in place, because "what did the
    -- file say" and "what are we about to write" are different questions and the
    -- review grid has to be able to show both.
    normalized         JSONB,
    -- VALID | ERROR | WARNING | SKIPPED | COMMITTED. SKIPPED is a user decision
    -- (excluded from the commit, still shown and still counted), not a failure -
    -- and on a stock-in row a blank quantity becomes SKIPPED silently rather than
    -- an error, which is what makes a 400-row pre-filled sheet usable for a 12-row
    -- delivery (design doc 5.3).
    status             VARCHAR(30) NOT NULL,
    -- [{column, message}, ...] - the same shape as ProductRowError minus the row
    -- number, which lives in excel_row above. Held as a document rather than a
    -- child table because it is read and written whole, exactly once per row
    -- revalidation, and is never queried across rows by its contents.
    errors             JSONB,
    -- The product this row matched (stock-in) or will update (catalog). A RAW
    -- UUID with no FK, deliberately, and for two reasons that both matter: the
    -- referent depends on kind, so no single FK target is correct; and a session
    -- is a work-in-progress whose resolution can be superseded by the user
    -- picking a different match in the review grid, so a constraint here would
    -- enforce a relationship that is still being decided. Same raw-UUID reasoning
    -- products.source_product_id and products.reviewed_by already state for their
    -- own out-of-band referents.
    resolved_entity_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_import_session_rows_status CHECK (
        status IN ('VALID', 'ERROR', 'WARNING', 'SKIPPED', 'COMMITTED')),
    CONSTRAINT chk_import_session_rows_excel_row_positive CHECK (excel_row > 0),
    -- One row per spreadsheet row per session. This is what makes PATCH
    -- /api/imports/{id}/rows/{rowId} idempotent against a re-parse and what stops
    -- a retried parse from silently doubling a file.
    CONSTRAINT uq_import_session_rows_session_id_excel_row UNIQUE (session_id, excel_row)

    -- No client_id. Reached only through session_id, itself a tenant-scoped
    -- import_sessions row - the same non-tenant-scoped-child pattern
    -- stock_movement_allocations and product_vendor_price_tiers already follow
    -- (V19). A direct lookup by row id with no join back to its session would be
    -- a mistake REGARDLESS of tenancy: a row means nothing without the session's
    -- kind, mode and column_mapping to interpret it against, so any correct read
    -- already has the session in hand and can scope on its client_id. Adding a
    -- client_id here would create a second place for the answer to live and a
    -- second way for it to be wrong.
);

-- The review grid's own read, and the reason "Issues" can be the default view
-- when errors exist: page this session's rows filtered to one status, in Excel
-- order. Nobody scrolls 300 rows looking for red (design doc 9.3).
CREATE INDEX idx_import_session_rows_session_id_status ON import_session_rows (session_id, status, excel_row);

CREATE TRIGGER trg_import_session_rows_set_updated_at
    BEFORE UPDATE ON import_session_rows
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE import_session_rows IS
    'One spreadsheet row of an import_sessions escrow, from parse through in-browser repair to '
    'commit. No client_id of its own - reached only through session_id, the same '
    'non-tenant-scoped-child pattern stock_movement_allocations and product_vendor_price_tiers '
    'follow, and for a stronger reason than tenancy: a row is uninterpretable without its '
    'session''s kind, mode and column_mapping, so every correct read already holds the session '
    'and can scope on its client_id. See BULK_IMPORT_DESIGN.md section 6.1.';

COMMENT ON COLUMN import_session_rows.excel_row IS
    '1-based, matching what the user sees in the spreadsheet - the ProductRowError convention, '
    'and what every error message in this feature points at ("Row 4").';

COMMENT ON COLUMN import_session_rows.raw IS
    'Exactly what the cells contained, never rewritten. A repair writes to normalized instead, '
    'so a fix stays inspectable against what the file actually said - and so the design doc '
    '6.6 undo has a pre-update snapshot to revert an updated product''s fields to.';

COMMENT ON COLUMN import_session_rows.normalized IS
    'Post-parse, post-repair values - what commit actually reads. Two columns rather than one '
    'mutated in place because "what did the file say" and "what are we about to write" are '
    'different questions, and the review grid has to answer both.';

COMMENT ON COLUMN import_session_rows.status IS
    'VALID | ERROR | WARNING | SKIPPED | COMMITTED. SKIPPED is a user decision - excluded from '
    'the commit, still shown, still counted - not a failure; a stock-in row with a blank '
    'quantity lands here silently rather than as an error, which is what makes a 400-row '
    'pre-filled sheet usable for a 12-row delivery (design doc 5.3).';

COMMENT ON COLUMN import_session_rows.errors IS
    '[{column, message}, ...] - ProductRowError''s shape minus the row number, which is '
    'excel_row. A document rather than a child table because it is read and written whole, '
    'once per row revalidation, and never queried across rows by its contents.';

COMMENT ON COLUMN import_session_rows.resolved_entity_id IS
    'The product this row matched (stock-in) or will update (catalog). A raw UUID with no FK: '
    'the referent depends on the session''s kind, and a resolution is still being DECIDED while '
    'the session is open - the user can pick a different match in the review grid - so a '
    'constraint here would enforce a relationship that is not settled yet. Same raw-UUID '
    'reasoning as products.source_product_id and products.reviewed_by.';

-- ============================================================================
-- stock_movements.occurred_at: WHEN THE DELIVERY HAPPENED, as distinct from
-- when the row was written. Design doc section 8.4, and not optional.
-- ============================================================================
-- FIFO consumes lots ordered by created_at (StockMovementRepository.
-- findInMovementsForUpdate). A bulk stock-in of last month's purchases inserts
-- them with today's timestamp, which places them AFTER stock that was genuinely
-- received earlier - so FIFO draws in the wrong order and the per-delivery trace
-- StockMovementAllocation exists to provide points at the wrong delivery. For a
-- feature whose entire stated purpose is "record what we bought outside the
-- platform", backdating is the NORMAL case, not an edge case. Shipping bulk
-- stock-in without this column means shipping a feature that quietly corrupts
-- FIFO order for its own primary use case.
--
-- created_at is not repurposed and not touched. It stays the immutable audit
-- fact of when the row was WRITTEN, which is exactly why it makes the right
-- stable tiebreak underneath occurred_at: two deliveries recorded as having
-- occurred on the same day still have a total order, and that order is the one
-- they were entered in, which never changes and never ties.
--
-- Three statements rather than one, because ADD COLUMN ... NOT NULL DEFAULT
-- now() would stamp every existing row with the MIGRATION's timestamp instead of
-- its own created_at - which is precisely the wrong answer for a column whose
-- whole job is to say when something actually happened.
ALTER TABLE stock_movements
    ADD COLUMN occurred_at TIMESTAMPTZ;

-- Backfill. Safe to run as a plain UPDATE: stock_movements has no append-only
-- database trigger (see this file's header - the V14/V16 guard covers
-- vendor_ledger_entries and vendor_settlement_settings_changes only), so there is
-- no maintenance hatch to open and no silently-no-opping UPDATE to walk into.
-- Expressed as "every row that does not have one yet" so it is correct whether
-- this runs on an empty database or out-of-order behind a seeded one.
UPDATE stock_movements SET occurred_at = created_at WHERE occurred_at IS NULL;

ALTER TABLE stock_movements
    ALTER COLUMN occurred_at SET NOT NULL,
    -- A default of now() so any writer that does not think about this column -
    -- a hand-written data fix, a seed script - still produces a row whose
    -- occurred_at means something sensible rather than failing on NOT NULL. The
    -- application always states it explicitly; see StockMovement.occurredAt.
    ALTER COLUMN occurred_at SET DEFAULT now();

-- Not in the future, with one day of grace. The grace is not slack, it is
-- clock skew: this is a Nigeria-facing app whose users' machines are on their
-- own clocks and whose browsers send a DATE for received_date, not an instant -
-- so a delivery entered as "today" from a device an hour ahead, or read as
-- midnight in a timezone ahead of the server's, is routinely a few hours past
-- now() through no fault of anybody. Refusing those would produce an error
-- message ("that date is in the future") that is simply false from where the
-- user is sitting, for the sake of a rule whose real purpose is to catch a
-- mistyped year. One day is comfortably wider than any timezone or skew a real
-- request carries and comfortably narrower than any typo worth catching.
--
-- The corresponding "warn, do not block, beyond some distance in the PAST"
-- half of design doc 8.4 is deliberately NOT a constraint: it is a warning on a
-- review row, and a warning is a thing the service layer produces, not a thing
-- the database refuses.
ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movements_occurred_at_not_future
        CHECK (occurred_at <= now() + INTERVAL '1 day');

-- The FIFO read, covered end to end: filter by product and movement type, then
-- consume in (occurred_at, created_at) order. Replaces the plain
-- idx_stock_movements_product_id from V1, which this index's leading column
-- subsumes - every read that used it (the history specification's product
-- filter, existsByProductIdAndClientId, and the product_id FK's own
-- delete-time check) is served by this index's product_id prefix, so keeping
-- both would mean maintaining two indexes for one access pattern.
CREATE INDEX idx_stock_movements_product_id_type_occurred_at
    ON stock_movements (product_id, movement_type, occurred_at, created_at);

DROP INDEX IF EXISTS idx_stock_movements_product_id;

COMMENT ON COLUMN stock_movements.occurred_at IS
    'When the delivery/sale/adjustment actually HAPPENED, as distinct from created_at, which '
    'stays the immutable audit fact of when this row was WRITTEN. FIFO orders lots by '
    '(occurred_at, created_at) - the second as a stable tiebreak that never ties and never '
    'changes. Backdating is the normal case for bulk stock-in ("record what we bought outside '
    'the platform"), which is why ordering by created_at alone silently corrupted FIFO for '
    'that feature''s primary use case. Constrained to be no more than one day in the future - '
    'that day is clock/timezone skew, not slack. See BULK_IMPORT_DESIGN.md section 8.4.';

-- ============================================================================
-- import_batch_id: what an import CREATED, on both things an import can create.
-- Design doc 6.5/6.6.
-- ============================================================================
-- "Every entity written by a commit carries the session_id as import_batch_id.
-- That is what makes [undo] possible and what lets the result screen link to
-- exactly what it created."
--
-- RESTRICT on both, not SET NULL and not CASCADE, and the reasoning differs
-- slightly per table but lands in the same place:
--   * stock_movements is append-only. SET NULL would have the DATABASE emit an
--     UPDATE against a row this schema promises is never edited - the one thing
--     the whole ledger design refuses. CASCADE would delete ledger rows to make
--     a session purge succeed, which design doc 6.6 rules out in as many words
--     ("Undo never deletes ledger rows").
--   * products keeps the link because the undo needs it: a catalog import's undo
--     deactivates the products it created, and it can only find them through
--     this column.
-- In both cases RESTRICT states the real invariant: a session that committed
-- something is not garbage to be collected. The expiry job only ever deletes
-- UNCOMMITTED sessions, so it never meets this constraint; if it ever did, the
-- refusal is the correct outcome and not an inconvenience to design around.
ALTER TABLE stock_movements
    ADD COLUMN import_batch_id UUID REFERENCES import_sessions (id) ON DELETE RESTRICT;

ALTER TABLE products
    ADD COLUMN import_batch_id UUID REFERENCES import_sessions (id) ON DELETE RESTRICT;

-- Both indexes serve the same two questions from opposite ends: the result
-- screen's "show me what this import created" (targetUrl
-- /app/products?importBatchId={id}) and the undo's "is any of it still cleanly
-- reversible". Partial on IS NOT NULL because the overwhelming majority of rows
-- in both tables were never imported and would otherwise be a very large run of
-- NULLs in an index nothing ever probes for NULL.
CREATE INDEX idx_stock_movements_import_batch_id
    ON stock_movements (import_batch_id)
    WHERE import_batch_id IS NOT NULL;

CREATE INDEX idx_products_import_batch_id
    ON products (import_batch_id)
    WHERE import_batch_id IS NOT NULL;

COMMENT ON COLUMN stock_movements.import_batch_id IS
    'The import_sessions row whose commit wrote this movement, or NULL for anything recorded '
    'by hand or by an order receipt. This is what "Undo this import" reads to find the lots a '
    'batch created - and, when any of them has been drawn from (stock_movement_allocations '
    'rows exist), what lets the refusal name the exact three deliveries that block it rather '
    'than failing vaguely. RESTRICT: a session that wrote ledger rows is not garbage to be '
    'collected, and SET NULL would mean the database itself editing an append-only row.';

COMMENT ON COLUMN products.import_batch_id IS
    'The import_sessions row whose commit created this product, or NULL for anything created '
    'by hand or from an order receipt. Read by the result screen''s "View products" link and '
    'by the design doc 6.6 undo, which deactivates the products a batch created (and is '
    'blocked for any that has since had a movement).';
