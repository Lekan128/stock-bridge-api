-- A sale is a stock-out that says so (SELLING_PRICE_PLAN.md sections 4.3, 4.3a).
--
-- There is no sales table, deliberately: a sale IS the stock-out the ledger
-- already writes, so sales can never disagree with stock. These columns add
-- what a stock-out did not say before - why it happened, what it went for, what
-- the stock it used had cost - and the two ways a sale is fixed afterwards,
-- both of which only ever ADD rows (plan principle 12).
--
-- Every column is nullable or defaulted and no existing row is touched. Every
-- one is written once and never updated, like the rest of the ledger.

-- ============================================================================
-- WHY a stock-out happened
-- ============================================================================
-- Null is "No reason given" - every row before this, and every stock-out where
-- nobody opened the optional control. The server never guesses one, except
-- that a stock-out carrying a price or a sale id is a SALE (the rule that keeps
-- an old client's priced stock-out meaning what it always meant).
ALTER TABLE stock_movements
    ADD COLUMN out_reason VARCHAR(24),
    ADD CONSTRAINT chk_stock_movements_out_reason CHECK (
        out_reason IS NULL
        OR (movement_type = 'OUT' AND out_reason IN (
            'SALE', 'USED', 'DAMAGED', 'EXPIRED', 'LOST', 'RETURNED_TO_SUPPLIER', 'OTHER')));

-- ============================================================================
-- THE MONEY
-- ============================================================================
-- sale_id groups the lines of one sale. It is made on the PHONE when the sale
-- starts, so a sale recorded offline is already one sale before it reaches the
-- server; a priced stock-out sent without one gets its own. Every row that
-- belongs to a sale - its lines, and any cancellation or price fix of them -
-- carries the same sale_id, which is how every report finds them.
--
-- sale_amount is what the line was SOLD FOR, exactly as charged: the typed line
-- total when there was one, else entered quantity x the price in the entered
-- unit. Revenue is SUM(sale_amount), never quantity x unit_price_at_time - that
-- product goes through a per-stock-unit price rounded to kobo, and 24 x 208.33
-- is not the 5,000 the customer paid for a carton.
--
-- cost_amount is what the stock this row used had cost, snapshotted when it
-- happened from the deliveries it drew on (each delivery's own price, FIFO).
-- A delivery with no price, or stock older than the ledger, is costed at the
-- product's average cost at that moment (cost_basis AVERAGE); with no cost to
-- be had anywhere the whole figure is null (UNKNOWN) - a missing number is not
-- zero, and half a cost is not a cost. Written for every stock-out, not only
-- sales: "used up ₦18,300 of stock" is the same fact for a restaurant.
ALTER TABLE stock_movements
    ADD COLUMN sale_id      UUID,
    ADD COLUMN sale_amount  NUMERIC(14, 2),
    ADD COLUMN cost_amount  NUMERIC(14, 2),
    ADD COLUMN cost_basis   VARCHAR(12),
    ADD CONSTRAINT chk_stock_movements_cost_basis CHECK (
        cost_basis IS NULL OR cost_basis IN ('FIFO', 'AVERAGE', 'UNKNOWN'));

-- ============================================================================
-- FIXING A SALE AFTERWARDS - two forms, both append-only
-- ============================================================================
-- reverses_movement_id: CANCELLING a sale line. An ADJUSTMENT that puts the
-- quantity back (+n), with sale_amount and cost_amount negated so every report
-- nets the line out without special cases, and with allocation rows of -n
-- against the very deliveries the line drew from, so "how much is left in this
-- delivery" is exact again. The original row is never touched. Unique: a line
-- is cancelled once.
--
-- corrects_movement_id: ADDING THE PRICE to a sale saved without one. An
-- ADJUSTMENT of zero quantity - no stock moves - carrying only the sale_amount.
-- Unique: a line's price is added once.
--
-- The two references are NO ACTION, not RESTRICT like the ledger's other
-- foreign keys: a catalog reset deletes a company's whole ledger in one
-- statement, and RESTRICT checks each row the moment it goes - a sale line
-- deleted before its cancellation would fail. NO ACTION checks once the
-- statement is done, when both are gone. Nothing else ever deletes either row
-- (Undo only removes a company's LATEST write, which nothing points at yet).
ALTER TABLE stock_movements
    ADD COLUMN reverses_movement_id UUID REFERENCES stock_movements (id),
    ADD COLUMN corrects_movement_id UUID REFERENCES stock_movements (id),
    ADD CONSTRAINT chk_stock_movements_fix_is_adjustment CHECK (
        (reverses_movement_id IS NULL AND corrects_movement_id IS NULL)
        OR (movement_type = 'ADJUSTMENT' AND NOT is_count
            AND (reverses_movement_id IS NULL OR corrects_movement_id IS NULL)));

CREATE UNIQUE INDEX uq_stock_movements_reverses_movement_id
    ON stock_movements (reverses_movement_id) WHERE reverses_movement_id IS NOT NULL;
CREATE UNIQUE INDEX uq_stock_movements_corrects_movement_id
    ON stock_movements (corrects_movement_id) WHERE corrects_movement_id IS NOT NULL;

-- A price fix moves no stock, so it is the second kind of ADJUSTMENT that may
-- record a zero (the first is a count that found what the books said, V38).
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_quantity_valid;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_quantity_valid CHECK (
    (movement_type IN ('IN', 'OUT') AND quantity > 0)
    OR (movement_type = 'ADJUSTMENT' AND (quantity <> 0 OR is_count OR corrects_movement_id IS NOT NULL))
);

-- A cancellation hands stock back to the deliveries a sale drew it from with a
-- NEGATIVE allocation, so the lot's remaining balance (its quantity minus the
-- sum of its allocations) comes back exactly. Any non-zero amount is now valid;
-- that only a cancellation writes a negative one is the service's rule - a
-- CHECK cannot see which movement wrote the row. Anything asking "has this
-- delivery ever been drawn from" must ask whether any allocation EXISTS, not
-- whether they sum above zero: a drawn-and-returned delivery sums to zero and
-- still has rows pointing at it.
ALTER TABLE stock_movement_allocations DROP CONSTRAINT chk_stock_movement_allocations_quantity_positive;
ALTER TABLE stock_movement_allocations ADD CONSTRAINT chk_stock_movement_allocations_quantity_nonzero
    CHECK (quantity <> 0);

-- The Sales report reads a company's sale rows by date, and a sale's rows by
-- its id ("this sale", Undo sale, Cancel sale). Partial: most rows are not
-- sales, and these indexes never need to hold them.
CREATE INDEX idx_stock_movements_sales_by_date
    ON stock_movements (client_id, occurred_at) WHERE sale_id IS NOT NULL;
CREATE INDEX idx_stock_movements_sale_id
    ON stock_movements (client_id, sale_id) WHERE sale_id IS NOT NULL;

COMMENT ON COLUMN stock_movements.out_reason IS
    'Why a stock-out happened (V46): SALE, USED, DAMAGED, EXPIRED, LOST, RETURNED_TO_SUPPLIER, OTHER. '
    'Null is "No reason given".';
COMMENT ON COLUMN stock_movements.sale_amount IS
    'What a sale line went for, exactly as charged (V46). Revenue is SUM(sale_amount). '
    'Negated on a cancellation row; the price alone on a price-fix row.';
COMMENT ON COLUMN stock_movements.cost_amount IS
    'Cost of the stock a stock-out used, snapshotted from its FIFO draws (V46); see cost_basis. '
    'Null when no cost could be known. Negated on a cancellation row.';
