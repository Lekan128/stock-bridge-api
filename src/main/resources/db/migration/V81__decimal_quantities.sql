-- Stock quantities hold three decimal places (UNITS_PLAN.md Part 0, owner 2026-11-08: "Shouldn't we use .5?").
--
-- A derica of rice is about 0.6 kg, so "5 dericas" is 3 kg and "7 dericas" is 4.2 kg: a stock counted in kg has
-- to hold a fraction of one. Three places is to the gram for kg and the millilitre for litres. Every value
-- already stored is a whole number and stays exactly what it was; only the column type changes. Pieces stay
-- whole: that is a rule the service keeps, not the column.
--
-- What changes: every figure the stock ledger keeps - movements and the lots they draw from, the product's,
-- branch's and area's figures, moves between areas and what came back of them, asks, transfers, and each
-- supplier's share. Unchanged: levels (a low-stock level and an area's usual amount stay whole), and marketplace
-- carts and orders, which are whole items bought from a supplier.
ALTER TABLE stock_movements ALTER COLUMN quantity TYPE NUMERIC(14, 3);
ALTER TABLE stock_movement_allocations ALTER COLUMN quantity TYPE NUMERIC(14, 3);
ALTER TABLE products ALTER COLUMN quantity_on_hand TYPE NUMERIC(14, 3);
ALTER TABLE branch_stock ALTER COLUMN quantity_on_hand TYPE NUMERIC(14, 3);
ALTER TABLE area_stock ALTER COLUMN quantity_on_hand TYPE NUMERIC(14, 3);
ALTER TABLE area_move_lines
    ALTER COLUMN quantity TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_confirmed TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_damaged TYPE NUMERIC(14, 3);
ALTER TABLE area_move_returns ALTER COLUMN quantity TYPE NUMERIC(14, 3);
ALTER TABLE stock_request_lines
    ALTER COLUMN quantity TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_moved TYPE NUMERIC(14, 3);
ALTER TABLE stock_transfer_lines
    ALTER COLUMN quantity_sent TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_received TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_missing TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_damaged TYPE NUMERIC(14, 3),
    ALTER COLUMN quantity_found TYPE NUMERIC(14, 3);
ALTER TABLE product_vendors
    ALTER COLUMN quantity_on_hand_from_vendor TYPE NUMERIC(14, 3),
    ALTER COLUMN total_quantity_received TYPE NUMERIC(14, 3);
