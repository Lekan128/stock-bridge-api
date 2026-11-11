-- Purchase orders carry prices (PURCHASE_ORDER_PLAN.md section 11.4, owner 2026-11-06: "Purchase order
-- comes with a price"). This reverses "no prices, no totals anywhere" from 2026-11-01.
--
-- The line price already exists (expected_delivery_lines.price, V33): what one of the ordered unit
-- costs - per bag, not per kg. Until now a purchase order left it empty on purpose; from now on the
-- form fills it in. Orders made before this keep it empty and the screens say "No prices".
--
-- What is new is the delivery charge: one optional amount for the whole order, added to its total.
-- Empty and zero are different: empty is "not said", zero is "free delivery". No VAT for now.
ALTER TABLE expected_deliveries
    ADD COLUMN delivery_charge NUMERIC(18, 2),
    ADD CONSTRAINT chk_expected_deliveries_delivery_charge CHECK (delivery_charge IS NULL OR delivery_charge >= 0);

-- A price on a line is never negative (a free sample is 0).
ALTER TABLE expected_delivery_lines
    ADD CONSTRAINT chk_expected_delivery_lines_price CHECK (price IS NULL OR price >= 0);
