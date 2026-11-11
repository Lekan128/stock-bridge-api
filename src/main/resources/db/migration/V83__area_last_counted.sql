-- When each area last counted each product (STOCK_SHEETS_AND_AREA_COUNT_PLAN.md Part 5): the closing count's
-- "Counted today". From now on every count writes area_stock.last_counted_at (V60 added the column, nothing wrote
-- it). This fills it once from the counts already in the ledger, so the first day isn't all "To count". A movement
-- with no area is its branch's original area's (D-E3).
UPDATE area_stock s
SET last_counted_at = counted.at
FROM (
    SELECT COALESCE(m.area_id, o.id) AS area_id, m.product_id, MAX(m.occurred_at) AS at
    FROM stock_movements m
    LEFT JOIN areas o ON o.branch_id = m.branch_id AND o.is_original
    WHERE m.is_count
    GROUP BY COALESCE(m.area_id, o.id), m.product_id
) counted
WHERE s.area_id = counted.area_id
  AND s.product_id = counted.product_id
  AND (s.last_counted_at IS NULL OR s.last_counted_at < counted.at);
