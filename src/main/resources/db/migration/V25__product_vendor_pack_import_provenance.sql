-- A pack confirmed while reviewing a bulk import (MULTI_PACK_PER_VENDOR_DESIGN.md section 6a)
-- is a guess accepted with one click, not a deliberate catalog edit like "+ Add pack" on the
-- Vendors tab. Two gaps followed from treating it identically to a manual pack:
--
--   1. Discarding the import session that produced it left the pack behind. ImportSessionService
--      .discard only ever deleted import_session_rows and the session itself - a real, permanent
--      product_vendor_packs row survived untouched, because confirm-pack persists independently
--      of the session's own lifecycle (StockInRowHandler.confirmPack's own doc comment: "needs
--      one specific vendor to exist right now, days before any commit").
--   2. On the next upload, that pack was now a legitimate answer in the product's unit set, so a
--      row typing the exact same (possibly wrong) counted_in value resolved cleanly - no error,
--      no warning, no visual difference from a pack that had been correct for years.
--
-- created_from_import_session_id lets a discard find what it left behind and offer to remove it.
-- needs_review lets the stock-in row handler keep asking for the one explicit confirmation this
-- pack never really got, on every row that matches it, until either that click happens or the
-- pack is edited into something else.
ALTER TABLE product_vendor_packs
    ADD COLUMN created_from_import_session_id UUID REFERENCES import_sessions (id) ON DELETE SET NULL,
    ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT FALSE;

-- The only query this column exists for: "what did this session add, in case it gets discarded".
CREATE INDEX idx_product_vendor_packs_created_from_import_session_id
    ON product_vendor_packs (created_from_import_session_id)
    WHERE created_from_import_session_id IS NOT NULL;

COMMENT ON COLUMN product_vendor_packs.created_from_import_session_id IS
    'The import session whose review screen confirmed this pack into existence, or NULL for a '
    'pack added directly on the Vendors tab. Set once, at creation, never updated - a session id '
    'that later stops existing (the 48-hour sweep) just leaves this NULL, which is fine: the '
    'discard-time offer only ever needs it while the session is still alive.';

COMMENT ON COLUMN product_vendor_packs.needs_review IS
    'TRUE for a pack that was accepted with one click during import review rather than added '
    'deliberately on the Vendors tab, until that click is explicitly given - see '
    'StockInRowHandler.validateCountedIn''s COUNTED_IN_PACK_UNCONFIRMED issue. A pack added via '
    '"+ Add pack" is never TRUE - that action already is the confirmation.';
