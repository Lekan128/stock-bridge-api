-- How an area's last count of a product was typed (owner, 2026-11-10: "it should show the counted units, the way the
-- user counted it"): [{"unit": "BAG:15", "quantity": 1}, {"unit": "KG", "quantity": 2}]. The count list's "Counted
-- today" reads "1 bag + 2 kg", and Count again opens with those lines filled in, on any phone. Null: counted before
-- this, or by something that sends only the total - the total is said instead.
ALTER TABLE area_stock
    ADD COLUMN last_counted_as JSONB;
