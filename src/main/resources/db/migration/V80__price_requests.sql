-- Asking several suppliers for prices (PURCHASE_ORDER_PLAN.md sections 11.3, owner 2026-11-06: "They should
-- be able to pick multiple suppliers for the quote and send it to multiple suppliers"; "will the vendor be
-- able to click the link sent to them to fill in the prices ... The vendors should also be able to add a
-- remark").
--
-- A price request (RFQ-0001...) is its own record, not an order: one request goes to several suppliers and
-- can become more than one purchase order. Each supplier on it has an unguessable link to type their prices
-- into, with no account; the buyer can also type a reply heard on the phone. Choosing turns the replies into
-- ordinary purchase orders (expected_deliveries), each remembering the request it came from.
CREATE TABLE price_requests (
    id             UUID PRIMARY KEY,
    client_id      UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    rfq_sequence   INTEGER      NOT NULL,
    rfq_number     VARCHAR(20)  NOT NULL,
    branch_id      UUID         REFERENCES branches (id) ON DELETE SET NULL,
    needed_by      DATE,
    reply_by       DATE,
    note           VARCHAR(500),
    -- OPEN: asking. DONE: became purchase orders. CLOSED: closed without ordering.
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'CLOSED')),
    created_by     UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ,
    CONSTRAINT uq_price_requests_number UNIQUE (client_id, rfq_sequence)
);

CREATE INDEX idx_price_requests_client_status ON price_requests (client_id, status, created_at DESC);

CREATE TABLE price_request_lines (
    id          UUID PRIMARY KEY,
    request_id  UUID           NOT NULL REFERENCES price_requests (id) ON DELETE CASCADE,
    product_id  UUID           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    unit        VARCHAR(64)    NOT NULL,
    quantity    NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    -- Frozen words, so the supplier's page reads the same if the product is renamed.
    product_name VARCHAR(255)  NOT NULL,
    comes_in    VARCHAR(100)   NOT NULL,
    position    INTEGER        NOT NULL
);

CREATE INDEX idx_price_request_lines_request ON price_request_lines (request_id, position);

-- One supplier asked. The token is the whole of the access check for their reply page (/q/<token>).
CREATE TABLE price_request_suppliers (
    id               UUID PRIMARY KEY,
    request_id       UUID          NOT NULL REFERENCES price_requests (id) ON DELETE CASCADE,
    vendor_id        UUID          NOT NULL REFERENCES company_vendors (id) ON DELETE CASCADE,
    token            VARCHAR(64)   NOT NULL,
    -- What the buyer did to send it (never "delivered"/"read"): WHATSAPP, MESSAGE (copied), EMAIL.
    sent_via         VARCHAR(16),
    sent_at          TIMESTAMPTZ,
    -- When the reply page really loaded on their phone (its script ran), not a link preview.
    opened_at        TIMESTAMPTZ,
    replied_at       TIMESTAMPTZ,
    -- LINK: the supplier typed it. TYPED: the buyer typed what they said on the phone.
    reply_source     VARCHAR(8)    CHECK (reply_source IS NULL OR reply_source IN ('LINK', 'TYPED')),
    delivery_charge  NUMERIC(18, 2) CHECK (delivery_charge IS NULL OR delivery_charge >= 0),
    -- NULL: not said. Otherwise the day they can deliver.
    can_deliver_on   DATE,
    remark           VARCHAR(500),
    declined         BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Became this purchase order when the buyer chose them.
    order_id         UUID          REFERENCES expected_deliveries (id) ON DELETE SET NULL,
    CONSTRAINT uq_price_request_suppliers_vendor UNIQUE (request_id, vendor_id)
);

CREATE UNIQUE INDEX uq_price_request_suppliers_token ON price_request_suppliers (token);

-- A supplier's price for one line: a price, or "I don't have this".
CREATE TABLE price_request_quotes (
    supplier_row_id UUID          NOT NULL REFERENCES price_request_suppliers (id) ON DELETE CASCADE,
    line_id         UUID          NOT NULL REFERENCES price_request_lines (id) ON DELETE CASCADE,
    price           NUMERIC(18, 2) CHECK (price IS NULL OR price >= 0),
    cannot_supply   BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (supplier_row_id, line_id)
);

-- Every reply as it was, so a changed reply keeps the old figures (owner pick 2, 2026-11-06).
CREATE TABLE price_request_reply_history (
    id              UUID PRIMARY KEY,
    supplier_row_id UUID          NOT NULL REFERENCES price_request_suppliers (id) ON DELETE CASCADE,
    replied_at      TIMESTAMPTZ   NOT NULL,
    reply_source    VARCHAR(8)    NOT NULL,
    total           NUMERIC(18, 2),
    reply           TEXT          NOT NULL
);

CREATE INDEX idx_price_request_reply_history_supplier ON price_request_reply_history (supplier_row_id, replied_at);

-- The purchase orders a request became say where they came from.
ALTER TABLE expected_deliveries ADD COLUMN price_request_id UUID REFERENCES price_requests (id) ON DELETE SET NULL;

-- To whoever asked, and those who can send: a supplier sent (or changed) their prices.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT', 'STOCK_REQUEST', 'STOCK_REQUEST_ANSWERED', 'AREA_RETURN',
    'PO_APPROVAL_WAITING', 'PO_APPROVAL_ANSWERED', 'PRICE_REPLY'));
