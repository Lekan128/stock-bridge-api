-- What the human actually typed, kept beside what the ledger recorded.
--
-- Three nullable display columns on stock_movements, pinned by UNIT_UX_CONTRACT.md
-- section 3.3. See UNIT_UX_REMEDIATION_PLAN.md section 1 for the defect that makes
-- them necessary and section 5.3 for the model they come from.
--
-- ============================================================================
-- THE DEFECT THESE COLUMNS MAKE VISIBLE
-- ============================================================================
-- A stock-in request carries a quantity, a price, and a "unit" naming which of
-- the product's units the quantity was typed in. Until now the service converted
-- the QUANTITY into the product's stock unit and left the PRICE alone - so
-- "20 bags at N45,000 per bag" on a product counted in kg, packed 50 kg to the
-- bag, was recorded as 1,000 kg at N45,000 per kg. A fifty-fold error, written
-- silently, and then compounded into every later weighted-average cost, with
-- nothing on the row to show what had happened.
--
-- The conversion half of that is fixed in the service (both halves now go
-- through one factor). These columns are the other half of the fix: the entry
-- as typed is now preserved next to the entry as recorded, so a receipt, a
-- history row and an audit can show both - contract non-negotiable 3, "what the
-- user typed and what the ledger records appear together" - and so any future
-- disagreement between the two is visible rather than silent. NetSuite's model:
-- store the transaction in the base unit, keep the entered unit beside it, show
-- what was typed, compute on the base.
--
-- ============================================================================
-- THESE ARE FACTS ABOUT THE ENTRY, NOT INPUTS TO ANY CALCULATION
-- ============================================================================
-- quantity and unit_price_at_time keep their existing meaning EXACTLY: base
-- units, and money per one stock unit. They remain the only numbers that
-- products.quantity_on_hand, the weighted-average cost, the FIFO order and
-- stock_movement_allocations are derived from. Nothing may sum entered_quantity
-- or blend entered_unit_price - contract section 3.3 says so flatly, and the
-- reason is that a second quantity column is how a ledger acquires two answers
-- to the same question and no way to tell which one an audit should believe.
--
-- No CHECK constraint enforces that, because a database cannot see which column
-- a service reads. What the database CAN say is that these columns are not the
-- ledger, and it says it here, in the comments below, where the next person to
-- add a report will look.
--
-- ============================================================================
-- NO BACKFILL, AND NULL HAS A DEFINED MEANING
-- ============================================================================
-- Every existing row gets NULL in all three, because there is nothing honest to
-- backfill: a movement written before these columns existed did not record which
-- unit its number was typed in. NULL therefore reads as "entered in the product's
-- stock unit", and every consumer falls back to quantity / unit_price_at_time.
-- That is not a guess - before this change a converted quantity was the only
-- thing ever stored, so those two columns are precisely what those rows meant.
--
-- Nullable also going forward, not just for history: a request that omits "unit"
-- is the ordinary case (every marketplace order receipt, every opening balance,
-- every pre-existing API client) and it has nothing extra to say. Storing the
-- stock unit's own code in entered_unit for those rows would turn "the user chose
-- a unit" into a fact we invented on their behalf.
--
-- No index. Nothing filters, joins or sorts on these; they are read only as part
-- of a row already located by its id, its product or its batch.
-- ============================================================================

ALTER TABLE stock_movements
    ADD COLUMN entered_unit       VARCHAR(32),
    ADD COLUMN entered_quantity   NUMERIC(14, 3),
    ADD COLUMN entered_unit_price NUMERIC(14, 2);

COMMENT ON COLUMN stock_movements.entered_unit IS
    'DISPLAY ONLY - never read to compute a balance or a cost (UNIT_UX_CONTRACT.md '
    'section 3.3). The unit code the human typed this entry in, e.g. BAG or T, as '
    'submitted. NULL when they used the product''s own stock unit, which is what '
    'every request that omits "unit" says and what every row written before V21 '
    'means. Consumers treat NULL as "entered in the stock unit" and fall back to '
    'quantity / unit_price_at_time. Validated by the service against the product''s '
    'derived unit set, not by a CHECK: which units a product accepts depends on that '
    'product''s own packaging and on the tenant''s data, which a constraint cannot see.';

COMMENT ON COLUMN stock_movements.entered_quantity IS
    'DISPLAY ONLY - see entered_unit. The number the human typed, counted in '
    'entered_unit: 20, where quantity says 1000. NUMERIC rather than integer '
    '(unlike quantity) because an entered amount is not constrained to whole stock '
    'units - being able to say "half a tonne" is the point of entering in another '
    'unit at all. NULL exactly when entered_unit is.';

COMMENT ON COLUMN stock_movements.entered_unit_price IS
    'DISPLAY ONLY - see entered_unit. The price the human typed, PER entered_unit: '
    'N45,000 per bag, where unit_price_at_time says N900 per kg. unit_price_at_time '
    'is and stays the per-stock-unit figure that costing reads; this is the typed '
    'figure kept beside it so a receipt can show both. NULL when the entry named no '
    'unit, or carried no price at all (a free sample, a correction) - which stays '
    'NULL rather than becoming a zero that would drag a weighted average down for no '
    'economic reason.';
