-- Undo for a stock write (INVENTORY_OFFLINE_AND_CHARACTER_PLAN.md, B2, decision D8).
--
-- For two minutes, the person who recorded a stock-in, stock-out or count can void it, as long as
-- nothing else has been recorded for that product since. A void removes the write as if it had
-- never been made, so everything the write changed has to come back exactly. Most of that can be
-- reversed from the movement itself (the quantity, the lots a stock-out drew from); what cannot is
-- kept here, as it was just before the write:
--
--   * the product's weighted-average cost price, which a priced stock-in re-blends and whose
--     rounding cannot be run backwards;
--   * the supplier pack's last price and SKU, which a supplier delivery overwrites.
--
-- A write that did something a void must not quietly undo - set up a supplier for the product,
-- or saved a new pack as a supplier's default - is recorded with a reason instead, and its void is
-- refused with that reason. Rows outlive the two-minute window only until the daily sweep, and go
-- with their movement when it is voided.
CREATE TABLE stock_write_undo (
    movement_id                UUID         PRIMARY KEY REFERENCES stock_movements (id) ON DELETE CASCADE,
    client_id                  UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    created_by                 UUID         NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    prior_cost_price           NUMERIC(14, 2),
    pack_id                    UUID,
    prior_pack_last_cost_price NUMERIC(14, 2),
    prior_pack_vendor_sku      VARCHAR(100),
    not_undoable_reason        VARCHAR(300)
);

-- The daily sweep's only query.
CREATE INDEX idx_stock_write_undo_created_at ON stock_write_undo (created_at);
