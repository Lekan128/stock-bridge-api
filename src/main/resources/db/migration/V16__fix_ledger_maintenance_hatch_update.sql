-- Fix the append-only guard's maintenance escape hatch, which silently no-opped
-- every UPDATE it was supposed to permit.
--
-- ============================================================================
-- WHAT WAS WRONG
-- ============================================================================
-- V14 gave vendor_ledger_entries_append_only() a deliberate, documented escape
-- for the one caller that genuinely needs one - a cleanup or a hand-written data
-- fix, run consciously, never from a request:
--
--     IF current_setting('app.ledger_maintenance', TRUE) = 'on' THEN
--         RETURN COALESCE(OLD, NEW);
--     END IF;
--
-- The trigger is BEFORE UPDATE OR DELETE FOR EACH ROW, and in a BEFORE row
-- trigger the RETURNED ROW IS THE ROW POSTGRES WRITES. On a DELETE, OLD is the
-- only row there is and returning it means "go ahead" - correct. On an UPDATE,
-- OLD is not null either, so COALESCE(OLD, NEW) never even looks at NEW: it
-- returns the row as it was, and Postgres dutifully writes the OLD values back
-- over themselves.
--
-- So with the flag ON, an UPDATE against these tables:
--   * succeeds,
--   * reports the rows it matched, so a client sees "3 rows affected",
--   * and changes absolutely nothing.
--
-- There is no error, no warning, and no way to tell from the return value. The
-- guard behaved correctly for DELETE - the only operation the escape hatch had
-- ever been used for - which is why nothing caught it for two migrations.
--
-- ============================================================================
-- WHY IT WAS INVISIBLE, AND THE NEAR MISS THAT FOUND IT
-- ============================================================================
-- V15 added vendor_ledger_entries.matures_at and had to backfill it, which is an
-- UPDATE and therefore the first thing in this repository ever to want the hatch
-- for an UPDATE. Reaching for the documented escape would have produced a
-- migration that:
--
--   * passed on a developer machine, because a dev database has no ledger rows at
--     all, so the backfill matched nothing and there was nothing to get wrong;
--   * left matures_at NULL on every row of any database with real history;
--   * and then died on the ALTER COLUMN ... SET NOT NULL immediately after -
--     mid-migration, on production, with the column half-applied.
--
-- V15 spotted it while writing that backfill and routed around it, dropping the
-- trigger for the duration instead (safe there specifically because a migration
-- already holds ACCESS EXCLUSIVE on the table). It left a warning in its header
-- addressed to the next person: "IF YOU ARE WRITING THAT HAND-CORRECTIVE DATA FIX
-- V14 ANTICIPATES, READ THIS PARAGRAPH FIRST." VendorSettlementIntegrationTest
-- carries the same warning on its setMaturity() helper.
--
-- A warning is not a fix. It only reaches somebody who happens to read the right
-- migration header before writing their data fix, and the failure it warns about
-- is the kind nobody re-checks: the statement said it worked. So the hatch is
-- fixed here, and those two warnings are superseded rather than deleted - an
-- applied migration is a historical record and is never edited, so V15's paragraph
-- stays exactly as it was and this file is what a reader is meant to find next.
--
-- ============================================================================
-- WHAT THIS CHANGES, AND WHAT IT DELIBERATELY DOES NOT
-- ============================================================================
-- CHANGED: with app.ledger_maintenance = 'on', an UPDATE now returns NEW and
-- therefore actually applies. A DELETE still returns OLD and still deletes.
--
-- NOT CHANGED, and this is the half that matters:
--   * The guard still REFUSES both UPDATE and DELETE when the flag is not set,
--     with the same message, the same restrict_violation SQLSTATE, and the same
--     instruction to post a reversing row naming reverses_entry_id. That refusal
--     is the entire reason the trigger exists and nothing here weakens it. The
--     ledger is still append-only.
--   * The hatch is not removed and not widened. It is still a session setting
--     rather than a permission or a service method, for V14's reason: it cannot
--     be reached by accident from a request, and it leaves the decision visible in
--     the script that made it. Nothing in the application sets it - grep says so.
--   * CHECK constraints are untouched and still apply. A BEFORE trigger returning
--     NEW hands the row on to constraint evaluation, so an UPDATE through the
--     hatch that violates chk_vendor_ledger_entries_maturity still fails, loudly,
--     as it always did.
--   * No data is touched. This file replaces a function body and nothing else.
--
-- ONE FUNCTION, TWO TABLES. V15 attached this same function to
-- vendor_settlement_settings_changes on the reasoning that one escape hatch should
-- cover every money-history table in the schema. CREATE OR REPLACE keeps that
-- true: both triggers pick up the fix, because both already point at this function
-- by name and neither trigger definition changes.
--
-- ============================================================================
-- WHY BRANCH ON TG_OP RATHER THAN PATCH THE COALESCE
-- ============================================================================
-- COALESCE(NEW, OLD) - the arguments the other way round - would also be correct
-- today, for the accidental reason that NEW is null on a DELETE. It would be
-- correct by coincidence rather than by statement, and it would silently become
-- wrong if this function were ever attached to a BEFORE INSERT trigger or an
-- INSTEAD OF trigger, where both rows can be populated. TG_OP says out loud which
-- operation is being permitted and which row that operation needs, so the next
-- reader does not have to reconstruct a null-ness argument to convince themselves
-- it is right. Money code should not be correct by coincidence.
CREATE OR REPLACE FUNCTION vendor_ledger_entries_append_only() RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('app.ledger_maintenance', TRUE) = 'on' THEN
        -- The returned row is the row that gets written. A DELETE needs the row
        -- being removed; an UPDATE needs the INCOMING one, or the statement
        -- succeeds and changes nothing.
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'vendor_ledger_entries is append-only (attempted %). Post a new, opposite-signed row that '
        'names the original in reverses_entry_id instead.', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION vendor_ledger_entries_append_only() IS
    'Refuses UPDATE and DELETE on the money-history tables (vendor_ledger_entries, '
    'vendor_settlement_settings_changes). SET LOCAL app.ledger_maintenance = ''on'' is the one '
    'documented escape, for cleanup and hand-written data fixes only - never from the application. '
    'V16 fixed that escape: it returned COALESCE(OLD, NEW), which made every permitted UPDATE a '
    'silent no-op that still reported rows affected.';
