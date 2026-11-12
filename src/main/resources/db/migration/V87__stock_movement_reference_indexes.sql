-- Indexes for the columns that point at a stock movement, so deleting movements doesn't read the whole
-- table once per row.
--
-- Postgres checks every foreign key into stock_movements when a movement is deleted. Only SuperAdmin's
-- "Reset demo" deletes movements (they are otherwise append-only), and it deletes thousands at once.
-- reverses_movement_id has only a partial unique index (WHERE used_area_id IS NULL), which the foreign-key
-- check can't use, so each deleted movement scanned all of stock_movements: a second demo reset on a
-- database with ~2M movements ran past 12 minutes (2026-11-12). The three others had no index at all.

CREATE INDEX IF NOT EXISTS idx_stock_movements_reverses_movement_id
    ON stock_movements (reverses_movement_id) WHERE reverses_movement_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stock_transfer_lines_out_movement_id
    ON stock_transfer_lines (out_movement_id) WHERE out_movement_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_area_move_lines_used_movement_id
    ON area_move_lines (used_movement_id) WHERE used_movement_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_area_move_returns_movement_id
    ON area_move_returns (movement_id);
