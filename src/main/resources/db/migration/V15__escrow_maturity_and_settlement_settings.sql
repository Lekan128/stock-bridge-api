-- The maturity hold: money accrues when the buyer confirms, and becomes payable
-- N days later. Plus the single-row table that holds N, and the audit log of every
-- change to it.
--
-- ============================================================================
-- WHAT THE OWNER ASKED FOR, VERBATIM, AND WHERE EACH CLAUSE LANDS
-- ============================================================================
--   "Money stays in escrow until the buyer confirms, and then 7 days after the
--    buyer confirms it, the money is scheduled for release. Each vendor gets
--    paid at a scheduled time, every 14 days [...] The 7 days should be
--    configurable and only be able to be changed by the super admin. They must
--    input their password and confirm they know what they are doing [...] After
--    they change it an email should also be sent to the super admins."
--
--   1. "stays in escrow until the buyer confirms"  -> UNCHANGED from V14. Accrual
--      still hangs off OrderStatus.RECEIVED, the BUYER's assertion. Nothing in
--      this file moves it.
--   2. "7 days after the buyer confirms, scheduled for release"  -> the new
--      vendor_ledger_entries.matures_at column below.
--   3. "paid at a scheduled time, every 14 days"  -> UNCHANGED. PayoutCadence's
--      fortnight is untouched by this file. See TWO CLOCKS below - conflating
--      them is the single most likely way to get this module wrong.
--   4. "configurable, super admin only, password, confirmation, email"  ->
--      vendor_settlement_settings and vendor_settlement_settings_changes below,
--      driven by VendorSettlementSettingsService.
--
-- ============================================================================
-- TWO CLOCKS. THEY ARE INDEPENDENT AND MUST STAY THAT WAY.
-- ============================================================================
--   MATURITY decides WHETHER a ledger entry may be paid at all.
--       matures_at = occurred_at + escrow_hold_days, stamped at accrual.
--   CADENCE decides WHEN a run happens and which entries it closes.
--       period_end = the fortnight boundary, from PayoutCadence. V14 defines it.
--
-- They are not two halves of one schedule and neither is derived from the other.
-- A line that matures on day 8 of a fortnight does NOT get paid on day 8: it
-- waits for the next fortnightly run like everything else. A line that matures on
-- day 15 misses the run that closed on day 14 and goes into the one after. The
-- hold can be changed to any value in range without touching payout day, and
-- payout day cannot be changed at all (V14 explains why the anchor is a constant).
--
-- The eligibility predicate therefore gains ONE clause and loses none:
--
--     V14:  unsettled AND occurred_at < period_end
--     V15:  unsettled AND matures_at  < period_end
--
-- matures_at >= occurred_at is a CHECK below, so the new predicate implies the
-- old one and no entry can slip in early. Note it compares against period_end -
-- the cadence CUTOFF - and not against now(). That is what keeps an
-- operator-triggered run reproducible: a run started late must pay exactly what a
-- run started on the boundary would have paid, and a now()-based maturity test
-- would quietly include everything that ripened while the operator was at lunch.
-- Being late changes WHEN a vendor is paid, never WHAT they are paid. Same
-- property, same reason, as V14's period_end.
--
-- ============================================================================
-- WHY MATURITY IS A STORED COLUMN AND NOT A COMPUTED PREDICATE
-- ============================================================================
-- The alternative was to store nothing and evaluate `occurred_at + (SELECT
-- escrow_hold_days FROM vendor_settlement_settings)` at query time. It is less
-- schema and it is wrong, for one reason that decides it:
--
--     A COMPUTED PREDICATE MAKES A SETTINGS CHANGE RETROACTIVE.
--
-- Raise the hold from 7 to 30 on a Tuesday and every vendor's already-confirmed
-- money - money we have already told them, on their statement, becomes payable on
-- a stated date - silently moves three weeks into the future. The vendor watches a
-- date they were promised move away from them, and no row anywhere records that it
-- did. That is the worst failure available in this module, because it is invisible
-- to the platform and extremely visible to the vendor.
--
-- Storing it makes the rule the honest one:
--
--     THE HOLD THAT APPLIES TO A SALE IS THE HOLD THAT WAS IN FORCE WHEN THE
--     BUYER CONFIRMED IT. A CHANGE AFFECTS FUTURE ACCRUALS ONLY.
--
-- ...which is the answer to the first question any operator will ask, so it is
-- also stated on VendorSettlementSettings, on the change endpoint, on the audit
-- table below, and on the super admin screen ABOVE the submit button.
--
-- And note what the append-only rule does for us here rather than to us. V14
-- forbids UPDATE on this table, so matures_at is not merely "not updated by
-- convention" - it CANNOT be restamped, by a service, by a data fix, or by an
-- admin screen nobody has written yet. The stored value can never need to change,
-- because the fact it records (what the terms were at the moment the money was
-- earned) can never change either. A stored value on an append-only row is the
-- one place where "denormalised" and "immutable" are the same word.
--
-- ============================================================================
-- WHICH KINDS ARE HELD, AND WHY A CORRECTION NEVER WAITS
-- ============================================================================
--   SALE_PROCEEDS       held.      occurred_at + hold.
--   COMMISSION          held.      The SAME matures_at as its proceeds row.
--   SALE_REVERSAL       immediate. matures_at = occurred_at.
--   COMMISSION_REVERSAL immediate. matures_at = occurred_at.
--   PAYOUT              immediate. matures_at = occurred_at. (Never settleable
--                                  anyway - findSettleable excludes the kind.)
--
-- The commission travels with its proceeds because they are one event stated
-- twice. If the fee matured before the sale it funds, a run in between would
-- claim a lone negative commission row, and the vendor's batch would show a
-- deduction with no sale behind it - a statement that does not add up, which
-- V14 spends four paragraphs making impossible.
--
-- A correction matures IMMEDIATELY, and this is the clause with teeth. The whole
-- point of a hold is to catch a refund inside the window; a refund that had to
-- wait out its own hold could arrive after the payout of the sale it reverses,
-- which is the precise failure the hold was introduced to prevent. So a negative
-- row is always eligible at once, and chk_vendor_ledger_entries_correction_is_immediate
-- makes that structural rather than a rule in a service somebody can edit.
--
-- The visible consequence, stated so it is not discovered: a refund inside the
-- window leaves a vendor with an immature +proceeds and a mature -reversal at the
-- same moment, so their "payable now" figure can be NEGATIVE while their total
-- balance is zero. That is arithmetically correct and it is the SAFE direction -
-- the run's net goes down, never up, so no money leaves for a sale that came
-- back. VendorEscrowPosition reports the two separately for exactly this reason,
-- and the vendor's screen explains it rather than clamping it.
--
-- ============================================================================
-- ZERO IS ALLOWED. THE UPPER BOUND IS 90.
-- ============================================================================
-- escrow_hold_days = 0 means "payable the moment the buyer confirms", which is
-- precisely V14's behaviour. It is permitted deliberately: it is the documented
-- way back to the previous model without a deploy, it is a coherent state rather
-- than a degenerate one (matures_at = occurred_at, which is exactly what every
-- correction row already carries), and forbidding it would mean the only way to
-- undo this module is a migration.
--
-- 90 days is the ceiling. Above a quarter a hold stops being a fraud window and
-- becomes working capital taken from a small business that has already shipped
-- the goods; it is also far past any dispute window this platform operates. The
-- number is a bound on damage, not a target - and it is a CHECK here as well as a
-- @Min/@Max on the request, because the request DTO is one refactor from being
-- bypassed and the table is not.
--
-- Worth knowing rather than discovering: a hold LONGER THAN 14 DAYS means money
-- confirmed in one fortnight can never be paid by the run that closes it, so
-- every vendor is systematically paid a cycle later. That is a legitimate policy
-- choice and it is not blocked, but the super admin screen says it out loud
-- before the operator confirms.
-- ============================================================================


-- ============================================================================
-- vendor_ledger_entries.matures_at
-- ============================================================================
ALTER TABLE vendor_ledger_entries
    ADD COLUMN matures_at TIMESTAMPTZ;

-- BACKFILL, and the one place in this repository outside the test suite that has
-- to get past V14's append-only trigger.
--
-- Adding a column is DDL and does not fire a FOR EACH ROW trigger, but filling it
-- in is an UPDATE and does.
--
-- ---------------------------------------------------------------------------
-- WHY NOT app.ledger_maintenance, WHICH IS THE DOCUMENTED ESCAPE HATCH
-- ---------------------------------------------------------------------------
-- Because it does not work for an UPDATE, and it does not work SILENTLY, which is
-- worse. V14's trigger function does:
--
--     IF current_setting('app.ledger_maintenance', TRUE) = 'on' THEN
--         RETURN COALESCE(OLD, NEW);
--
-- In a BEFORE UPDATE trigger, returning OLD means "write the row unchanged". So
-- with the setting on, an UPDATE succeeds, reports its rows affected, and changes
-- nothing at all. The hatch was written for the integration suite's DELETEs, where
-- COALESCE(OLD, NEW) is exactly right, and nobody had reason to notice.
--
-- Used here it would have been quietly catastrophic: the backfill would leave
-- every matures_at NULL, and the SET NOT NULL below would then fail the migration
-- on any database that already holds ledger rows - which is to say on every
-- environment except a brand-new one. <<< IF YOU ARE WRITING THAT HAND-CORRECTIVE
-- DATA FIX V14 ANTICIPATES, READ THIS PARAGRAPH FIRST. >>>
--
-- Dropping the trigger for the duration is unambiguous and needs no session state.
-- It is safe here specifically because it is a migration: the ALTER TABLE above
-- already holds an ACCESS EXCLUSIVE lock on this table for the whole transaction,
-- so nothing else can write a row into the window where the guard is off.
--
-- ---------------------------------------------------------------------------
-- WHY occurred_at IS THE HONEST VALUE
-- ---------------------------------------------------------------------------
-- Every entry already in this table accrued under V14, where a row was
-- settle-eligible the moment it was written - so occurred_at IS the moment it
-- matured, and stamping it records what actually happened. Backfilling
-- occurred_at + 7 would retroactively impose a hold on money that was never
-- subject to one, which is precisely the retroactivity this whole file argues
-- against.
--
-- On a fresh database the UPDATE matches nothing and the two ALTERs are no-ops
-- either way.
ALTER TABLE vendor_ledger_entries DISABLE TRIGGER trg_vendor_ledger_entries_append_only;
UPDATE vendor_ledger_entries SET matures_at = occurred_at WHERE matures_at IS NULL;
ALTER TABLE vendor_ledger_entries ENABLE TRIGGER trg_vendor_ledger_entries_append_only;

ALTER TABLE vendor_ledger_entries
    ALTER COLUMN matures_at SET NOT NULL;

-- Never before the event it belongs to. This is what makes the new eligibility
-- predicate strictly narrower than V14's rather than merely different, so no
-- entry can be paid earlier under V15 than it would have been under V14.
ALTER TABLE vendor_ledger_entries
    ADD CONSTRAINT chk_vendor_ledger_entries_maturity
        CHECK (matures_at >= occurred_at);

-- A correction never waits. See "WHICH KINDS ARE HELD" above: a refund that had
-- to serve its own hold could land after the payout of the sale it reverses,
-- which is the failure the hold exists to prevent, arriving through the hold.
ALTER TABLE vendor_ledger_entries
    ADD CONSTRAINT chk_vendor_ledger_entries_correction_is_immediate
        CHECK (entry_type IN ('SALE_PROCEEDS', 'COMMISSION') OR matures_at = occurred_at);

-- The eligibility query's own index, replacing the role
-- idx_vendor_ledger_entries_seller_occurred_at played for findSettleable. That
-- one is kept: it still serves the STATEMENT, which windows on occurred_at
-- because a statement is dated by when the money was earned, not by when it
-- ripened. The two indexes answer two genuinely different questions and neither
-- is redundant.
CREATE INDEX idx_vendor_ledger_entries_seller_matures_at
    ON vendor_ledger_entries (seller_client_id, matures_at);

COMMENT ON COLUMN vendor_ledger_entries.matures_at IS
    'When this entry becomes payout-eligible: occurred_at + the escrow hold in force AT ACCRUAL. '
    'Stamped once and never restamped - the table is append-only - so changing the hold affects '
    'future accruals only. Corrections (reversals, payouts) carry matures_at = occurred_at.';


-- ============================================================================
-- vendor_settlement_settings: the hold, as data an operator can change.
-- ============================================================================
-- Single-row table, modelled on marketplace_settings (V6) down to the singleton
-- column and its unique index, because consistency beats novelty and this
-- application already has a shape for "commercial rules ops changes without a
-- redeploy".
--
-- WHY A SECOND TABLE INSTEAD OF A COLUMN ON marketplace_settings
-- They are gated by different principals, and that is not a detail. Every column
-- on marketplace_settings is writable by a platform-owner TENANT admin holding
-- MANAGE_MARKETPLACE_SETTINGS, through PUT /api/marketplace/admin/settings, with
-- no re-authentication of any kind. This value is writable only by a SUPER ADMIN
-- who re-enters their password and acknowledges the consequence. Putting them in
-- one row would put one UPDATE path under two authorisation stories, and the
-- weaker one would be one added request field away from moving money. A second
-- table makes the boundary structural.
--
-- WHY NOT A SPRING PROPERTY
-- Considered and insufficient on its own terms: the owner asked for a change made
-- from the UI, re-authenticated and audited. A property needs a deploy, cannot
-- carry who changed it, and has nowhere to hang the audit trail the first payment
-- dispute will need. There is deliberately no property that can override this row
-- either - two sources of truth for a money rule is how they disagree.
CREATE TABLE vendor_settlement_settings (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Always true; it exists only to carry the unique index that makes this a
    -- single-row table in the database rather than by convention. Same trick,
    -- same reason, as marketplace_settings.
    singleton         BOOLEAN NOT NULL DEFAULT TRUE,

    -- The hold, in whole days. Days and not an interval: the owner said "7 days",
    -- every screen says days, and an interval column would invite somebody to set
    -- PT36H and produce a maturity time nobody can read off a statement.
    escrow_hold_days  INTEGER NOT NULL DEFAULT 7,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_vendor_settlement_settings_singleton UNIQUE (singleton),
    CONSTRAINT chk_vendor_settlement_settings_singleton CHECK (singleton),
    -- Zero allowed, negative impossible, 90 the ceiling. See the header for all
    -- three arguments. Stated here as well as on the request DTO because a DTO is
    -- one refactor from being bypassed and a CHECK is not.
    CONSTRAINT chk_vendor_settlement_settings_hold_days
        CHECK (escrow_hold_days >= 0 AND escrow_hold_days <= 90)
);

CREATE TRIGGER trg_vendor_settlement_settings_set_updated_at
    BEFORE UPDATE ON vendor_settlement_settings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- The one row. escrow_hold_days takes its column default, so 7 - the number the
-- owner said - is defined in exactly one place.
INSERT INTO vendor_settlement_settings (singleton)
VALUES (TRUE)
ON CONFLICT (singleton) DO NOTHING;

COMMENT ON TABLE vendor_settlement_settings IS
    'Single-row settlement policy. escrow_hold_days is how long after a buyer confirms receipt a '
    'vendor''s money becomes payout-eligible. Changed only by a super admin, with password '
    're-entry and an explicit acknowledgement; every change is logged in '
    'vendor_settlement_settings_changes. A change affects FUTURE accruals only.';
COMMENT ON COLUMN vendor_settlement_settings.escrow_hold_days IS
    'Days between buyer confirmation and payout eligibility. Stamped onto vendor_ledger_entries.'
    'matures_at at accrual, so changing it never moves money that has already accrued. 0 means '
    'payable on confirmation; 90 is the ceiling.';


-- ============================================================================
-- vendor_settlement_settings_changes: who changed the money rule, and to what.
-- ============================================================================
-- The settings row above holds the CURRENT value and nothing else - it is one row
-- that is UPDATEd, so it has no memory. This table is the memory, and it is the
-- half that matters the first time a vendor and the platform disagree about when
-- something should have been paid: answering "what was the hold on 3 March"
-- requires a row that says so.
--
-- APPEND-ONLY, by the same trigger function V14 wrote for the ledger.
-- An audit trail that can be edited is not an audit trail, and the routes that
-- would edit it are the ones nobody has written yet. Reusing
-- vendor_ledger_entries_append_only() rather than declaring a twin means there is
-- ONE escape hatch (app.ledger_maintenance) covering every money-history table in
-- the schema, so a reader who has learned the ledger's rule already knows this
-- one - and the test suite's existing cleanup vocabulary works here unchanged.
--
-- ONE ROW PER CHANGE, NOT PER REQUEST. A request that sets the hold to the value
-- it already has writes nothing here and sends no email: it changed nothing, and
-- an audit trail padded with no-ops is one nobody reads. See
-- VendorSettlementSettingsService.
CREATE TABLE vendor_settlement_settings_changes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- What moved. Both sides, on the row, so a single line of this table is a
    -- complete statement without joining to its neighbours - which matters
    -- because the neighbour that would supply the "from" is the previous row, and
    -- reconstructing history by walking a table in order is how off-by-one
    -- answers get given under oath.
    previous_hold_days  INTEGER NOT NULL,
    new_hold_days       INTEGER NOT NULL,

    -- Who. SET NULL, matching vendor_payout_batches.run_by: an operator leaving
    -- the company must not delete the record of the decisions they made.
    changed_by          UUID REFERENCES super_admins (id) ON DELETE SET NULL,
    -- ...which is exactly why the username is SNAPSHOTTED alongside the id rather
    -- than joined for. Once changed_by is nulled by a deletion, the id column
    -- alone would leave a change with no human on it at all, and "somebody
    -- changed how money moves" is not an audit record. Same argument
    -- order_items.product_name makes for snapshotting a name onto a sold line.
    changed_by_username VARCHAR(255) NOT NULL,

    -- Why, when the operator said. Optional: a mandatory reason field on a form
    -- gets "asdf" typed into it, which is worse than an honest blank.
    reason              VARCHAR(500),

    changed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The same bounds the settings table carries, restated because this row is
    -- read on its own and a history containing a value the settings table would
    -- refuse is a history that has been tampered with.
    CONSTRAINT chk_vendor_settlement_settings_changes_bounds
        CHECK (previous_hold_days >= 0 AND previous_hold_days <= 90
            AND new_hold_days >= 0 AND new_hold_days <= 90),
    -- A "change" that changed nothing is not a change. The service returns early
    -- rather than writing one; this makes sure nothing else can.
    CONSTRAINT chk_vendor_settlement_settings_changes_actually_changed
        CHECK (new_hold_days <> previous_hold_days)
);

-- The screen's own query: newest first.
CREATE INDEX idx_vendor_settlement_settings_changes_changed_at
    ON vendor_settlement_settings_changes (changed_at DESC);

CREATE TRIGGER trg_vendor_settlement_settings_changes_append_only
    BEFORE UPDATE OR DELETE ON vendor_settlement_settings_changes
    FOR EACH ROW
EXECUTE FUNCTION vendor_ledger_entries_append_only();

COMMENT ON TABLE vendor_settlement_settings_changes IS
    'Append-only history of every change to the escrow hold: from, to, who, when and why. Shares '
    'vendor_ledger_entries'' append-only trigger, so the same app.ledger_maintenance session '
    'setting is the only escape. Never UPDATE or DELETE.';
