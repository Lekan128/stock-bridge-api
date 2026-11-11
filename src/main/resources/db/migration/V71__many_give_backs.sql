-- Areas, Part 6 (STOCK_AREAS_PART_6.md): Housekeeping may give back part of a give more than once (1 bag on Monday,
-- another on Tuesday), each an adjustment that reverses part of the same Used stock-out. V46 allowed one reversal
-- per row - right for a cancelled sale, which is reversed whole, once. That stays true for everything but a give
-- back, which is the only reversal that carries the area it came back from (used_area_id); how much comes back is
-- capped by AreaReturnService against area_move_returns.
DROP INDEX uq_stock_movements_reverses_movement_id;
CREATE UNIQUE INDEX uq_stock_movements_reverses_movement_id
    ON stock_movements (reverses_movement_id) WHERE reverses_movement_id IS NOT NULL AND used_area_id IS NULL;
