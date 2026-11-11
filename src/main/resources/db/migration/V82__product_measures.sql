-- A product's own units, each with what one holds (UNITS_PLAN.md Part 1, owner 2026-11-08: "mudu, cup, ... should be
-- units ... each unit that you are adding to a product must have its conversion ... a restaurant buys a bag of rice of
-- 80 kg but uses it in derica or cups").
--
-- A measure is set once, on the product: a derica of THIS rice is about 0.6 kg, of these beans about 0.75 kg, so the
-- figure belongs to the product, not the company, and is weighed by the kitchen rather than suggested by us. Kept on
-- the product row as a small list - [{"name": "Derica", "size": 0.6}] - because every place that reads a product's
-- units (the stock sheets, quick mode, the phone's catalogue, the sheets) already has the row in hand, and a separate
-- table would cost a query per product on every page. A measure that has been used is hidden rather than removed
-- ({"name": "Cup", "size": 0.2, "hidden": true}), so the entries made in it still read as they were typed.
--
-- used_in_unit is where the stock-out, count, move and quick-mode boxes start for this product (Part 2): "Derica",
-- a unit code ("KG"), or a pack ("BAG:50"). Null: the stock unit, as before.
ALTER TABLE products
    ADD COLUMN measures JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN used_in_unit VARCHAR(40);
