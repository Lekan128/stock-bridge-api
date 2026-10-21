-- The product's usual selling price at the moment of a sale, kept on the sale
-- line (SELLING_PRICE_PLAN.md sections 4.7, 6.11).
--
-- "Sold below the usual price" is one of the three things an owner checks for
-- leaks, with who recorded it. It has to be judged against the price as it
-- stood WHEN the sale happened: compared with today's price, every sale made
-- before a price rise would be flagged, and every one made before a cut would
-- hide. So the line snapshots it, per stock unit like unit_price_at_time beside
-- it - the same "freeze what was true at the time" rule as the rest of the row.
-- Null on every row that is not a sale, on sales of a product with no usual
-- price, and on every row written before this.
ALTER TABLE stock_movements
    ADD COLUMN usual_price_at_time NUMERIC(14, 2);

COMMENT ON COLUMN stock_movements.usual_price_at_time IS
    'The product''s selling price per stock unit when this sale line was recorded (V48) - '
    'what "sold below the usual price" is judged against.';
