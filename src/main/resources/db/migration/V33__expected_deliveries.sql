-- What a company has ordered from an off-platform supplier and is still waiting for
-- (BULK_IMPORT_CX_PLAN.md task 3.1).
--
-- Deliberately NOT a purchase order: no number, no approval, no supplier-facing document. It
-- exists to answer "what have we got coming?" and to save typing the delivery again when it
-- arrives. It also deliberately does not touch products.incoming_quantity, which means "paid for
-- through the marketplace and on its way" and is maintained under a row lock beside a real
-- ledger - an expectation is a promise someone made on the phone, and merging the two would put a
-- guess into a number the marketplace treats as settled.
CREATE TABLE expected_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id     UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    vendor_id     UUID REFERENCES company_vendors (id) ON DELETE SET NULL,
    expected_date DATE,
    reference     VARCHAR(200),
    note          VARCHAR(500),
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_expected_deliveries_status
        CHECK (status IN ('OPEN', 'RECEIVED', 'CANCELLED'))
);

-- The list screen's only query: this company's, newest first, usually filtered to OPEN.
CREATE INDEX idx_expected_deliveries_client_status
    ON expected_deliveries (client_id, status, created_at DESC);

CREATE TABLE expected_delivery_lines (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expected_delivery_id UUID NOT NULL REFERENCES expected_deliveries (id) ON DELETE CASCADE,
    product_id           UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- The same UnitOptions key a typed delivery line carries ("BAG:50", "KG"), so what was
    -- ordered is held in the words it was ordered in and nothing is converted until it is
    -- actually received.
    unit                 VARCHAR(64) NOT NULL,
    quantity             NUMERIC(18, 4) NOT NULL,
    price                NUMERIC(18, 2),
    -- Partial receipts are expressed here, per line, rather than as a status on the parent.
    received_quantity    NUMERIC(18, 4) NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_expected_delivery_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_expected_delivery_lines_received_not_negative CHECK (received_quantity >= 0)
);

CREATE INDEX idx_expected_delivery_lines_delivery ON expected_delivery_lines (expected_delivery_id);

-- "How many of this are coming?" is read per product, for the product list and the product page.
CREATE INDEX idx_expected_delivery_lines_product ON expected_delivery_lines (product_id);

-- Which expectation a stock-in import is receiving, so committing it can credit the lines and
-- undoing it can take them back. Null for every ordinary upload and every delivery typed from
-- scratch, which is the overwhelming majority.
ALTER TABLE import_sessions
    ADD COLUMN expected_delivery_id UUID REFERENCES expected_deliveries (id) ON DELETE SET NULL;
