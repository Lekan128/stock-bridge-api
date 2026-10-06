-- Marks the adjustments that ARE stock counts (INVENTORY_OFFLINE_AND_CHARACTER_PLAN.md, A4, D1).
--
-- A count ("40 kg on the shelf at 10:05") is recorded as an ADJUSTMENT, like any correction, so
-- reports and the movement history treat it the same. It differs in what it MEANS, and phones that
-- were offline need that meaning: a count is a fact about the shelf at a moment, so
--   - an older count arriving late is superseded by a newer one, and
--   - a sale or delivery from before the count, arriving late from an offline phone, is already
--     reflected in it and must not move the shelf figure a second time.
-- Ordinary adjustments are corrections, not observations, and keep their existing behaviour.
ALTER TABLE stock_movements ADD COLUMN is_count BOOLEAN NOT NULL DEFAULT FALSE;

-- "Was this product counted after time T?" - asked on every late write and every count.
CREATE INDEX idx_stock_movements_counts ON stock_movements (product_id, occurred_at) WHERE is_count;

-- A count that finds exactly what the books say changes nothing, but it is still an observation:
-- later late writes and older counts are judged against it. So a COUNT may record a difference of
-- zero; every other ADJUSTMENT still may not.
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_quantity_valid;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_quantity_valid CHECK (
    (movement_type IN ('IN', 'OUT') AND quantity > 0)
    OR (movement_type = 'ADJUSTMENT' AND (quantity <> 0 OR is_count))
);
