-- Sending goods back at the gate (PURCHASE_ORDER_PLAN.md section 11.6, owner 2026-11-06: "If they bring
-- what you don't like you can reject or you can accept based on reasons." For now only at the gate).
--
-- A typed delivery's lines say how many came and how many of those went back with the driver, and why.
-- What went back never becomes stock: the delivery's stock-in rows carry only what was taken. This table
-- keeps what went back, beside the delivery it came on, so the order's history and the supplier's can
-- say "3 bags sent back · Damaged". It counts only once that delivery is committed (and stops counting if
-- the delivery is undone): readers join to import_sessions and look at its status.
--
-- What was sent back is still owed on the order - nothing credits it - so "Is the rest still coming?"
-- covers it the same way it covers goods that came short.
CREATE TABLE delivery_send_backs (
    id                   UUID PRIMARY KEY,
    client_id            UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    import_session_id    UUID          NOT NULL REFERENCES import_sessions (id) ON DELETE CASCADE,
    expected_delivery_id UUID          REFERENCES expected_deliveries (id) ON DELETE SET NULL,
    product_id           UUID          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    unit                 VARCHAR(64)   NOT NULL,
    quantity             NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    reason               VARCHAR(20)   NOT NULL
        CHECK (reason IN ('WRONG_ITEM', 'DAMAGED', 'EXPIRED', 'NOT_ORDERED', 'PRICE', 'OTHER')),
    note                 VARCHAR(200),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_send_backs_session ON delivery_send_backs (import_session_id);
CREATE INDEX idx_delivery_send_backs_order ON delivery_send_backs (expected_delivery_id) WHERE expected_delivery_id IS NOT NULL;
