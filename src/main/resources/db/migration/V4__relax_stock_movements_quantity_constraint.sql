-- ADJUSTMENT movements record a signed delta (negative when the corrected
-- count is lower than what was on hand), so quantity_on_hand's new value can
-- be reconstructed from the ledger alone. IN/OUT keep storing a positive
-- magnitude - quantity is only ever a raw count for those, direction is
-- implied by movement_type. A zero-delta adjustment isn't recorded at all
-- (see StockManagementService), so ADJUSTMENT rows are never zero either.
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_quantity_positive;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_quantity_valid CHECK (
    (movement_type IN ('IN', 'OUT') AND quantity > 0)
    OR (movement_type = 'ADJUSTMENT' AND quantity <> 0)
);
