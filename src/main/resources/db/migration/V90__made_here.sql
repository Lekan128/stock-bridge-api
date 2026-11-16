-- Selling till Step 4 (SELLING_TILL_PLAN.md, owner 2026-11-16): things a company makes and sells but does not
-- keep in stock - a meal, a mixed drink, room service, laundry, a school lunch. "Made here", against "kept in
-- stock". Sold like any product; never counted, delivered, ordered or low. Step 5 gives one a recipe.

ALTER TABLE products
    ADD COLUMN made_here      BOOLEAN NOT NULL DEFAULT FALSE,
    -- "Rough cost to make one", per stock unit, until a recipe works it out: so profit shows from day one.
    ADD COLUMN estimated_cost NUMERIC(14, 2) CHECK (estimated_cost IS NULL OR estimated_cost >= 0);

-- Sold under selling, with its own switch: off until the owner (or support) turns it on.
ALTER TABLE clients
    ADD COLUMN sells_made_here BOOLEAN NOT NULL DEFAULT FALSE;

-- A made-here item's sale is costed from its rough cost.
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_cost_basis;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_cost_basis CHECK (
    cost_basis IS NULL OR cost_basis IN ('FIFO', 'AVERAGE', 'UNKNOWN', 'ESTIMATE'));
