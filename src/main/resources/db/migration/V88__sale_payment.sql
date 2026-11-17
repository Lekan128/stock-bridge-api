-- Selling till, Step 1 (SELLING_TILL_PLAN.md): how the customer paid for a sale.
--
-- One row per sale, keyed by the sale id the phone made (stock_movements.sale_id). It is written with
-- the sale's first line, so an offline sale needs no extra write. Later steps (open bills, split
-- payments, tax) hang their facts on this row too. A sale from before this table has no row: its
-- payment reads as "Not recorded", never a guess.
CREATE TABLE sales (
    id                  UUID PRIMARY KEY,
    client_id           UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id           UUID REFERENCES branches (id) ON DELETE SET NULL,
    payment_method      VARCHAR(16) NOT NULL CHECK (payment_method IN ('CASH', 'TRANSFER', 'CARD')),
    recorded_by         UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- "Change how they paid": who changed it last, and when. Null: as first recorded.
    payment_changed_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    payment_changed_at  TIMESTAMPTZ
);

CREATE INDEX idx_sales_client ON sales (client_id);
