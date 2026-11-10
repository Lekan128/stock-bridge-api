-- The units a product is counted in (owner 2026-11-10: "the default unit they use for counting should be known so they
-- don't have to do the gymnastics over and over"). Set once on the product, as MarginEdge's "How do you want to count
-- this product?": a count of it opens with one box per unit here - ["BAG:80", "KG"] - and the counter taps another
-- unit on or off for that count only. Each entry is a unit key as used_in_unit holds one. Empty: its pack and its
-- stock unit, as before.
ALTER TABLE products
    ADD COLUMN count_in JSONB NOT NULL DEFAULT '[]'::jsonb;
