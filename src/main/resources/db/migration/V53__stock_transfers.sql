-- Multiple branches, Phase 2 (MULTI_BRANCH_BUILD_PLAN.md sections 3.2 and 5): moving stock between a
-- company's branches. A transfer is two ledger rows - TRANSFER_OUT where it left, TRANSFER_IN where it
-- arrived - tied together by a stock_transfers record, the waybill.

-- The short code a waybill number carries (WB-BOL-0042). Given to a branch the first time it sends
-- stock (TransferService), so nothing that creates a branch - signup, the seeds - has to know about it.
ALTER TABLE branches ADD COLUMN code VARCHAR(8);
CREATE UNIQUE INDEX uq_branches_client_code ON branches (client_id, code) WHERE code IS NOT NULL;

-- The two new movement types (D-B4). Every reader of movement_type gives them a sign on purpose
-- (section 4.3); a report that only knows IN and OUT leaves them out, which is right for company
-- totals - a move between branches is neither a delivery nor a sale.
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_movement_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_movement_type
    CHECK (movement_type IN ('IN', 'OUT', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN'));

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_quantity_valid;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_quantity_valid CHECK (
    (movement_type IN ('IN', 'OUT', 'TRANSFER_OUT', 'TRANSFER_IN') AND quantity > 0)
    OR (movement_type = 'ADJUSTMENT' AND (quantity <> 0 OR is_count OR corrects_movement_id IS NOT NULL))
);

-- The waybill. Its id is made on the phone that sends it, so a send resent after a lost reply, or
-- sent late from a phone that was offline, is recorded once.
CREATE TABLE stock_transfers (
    id                 UUID PRIMARY KEY,
    client_id          UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- Per company, in order of sending. Shown as WB-<sending branch's code>-0042.
    number             INTEGER       NOT NULL,
    from_branch_id     UUID          NOT NULL REFERENCES branches (id),
    to_branch_id       UUID          NOT NULL REFERENCES branches (id),
    -- ON_THE_WAY, PARTLY_RECEIVED, RECEIVED, RECEIVED_SHORT, CANCELLED
    status             VARCHAR(20)   NOT NULL,
    sent_by            UUID          REFERENCES users (id) ON DELETE SET NULL,
    -- When it left, as recorded on the phone.
    sent_at            TIMESTAMPTZ   NOT NULL,
    carried_by_name    VARCHAR(120),
    carried_by_phone   VARCHAR(50),
    note               VARCHAR(1000),
    -- The last receipt's, for the list; every receipt is in stock_transfer_receipts.
    received_by        UUID          REFERENCES users (id) ON DELETE SET NULL,
    received_at        TIMESTAMPTZ,
    -- Missing stock stays in view until somebody who works at both branches says what happened
    -- (MULTI_BRANCH_UX_PLAN.md section 5.7).
    missing_checked_by UUID          REFERENCES users (id) ON DELETE SET NULL,
    missing_checked_at TIMESTAMPTZ,
    missing_note       VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_stock_transfers_branches_differ CHECK (from_branch_id <> to_branch_id),
    CONSTRAINT chk_stock_transfers_status CHECK (
        status IN ('ON_THE_WAY', 'PARTLY_RECEIVED', 'RECEIVED', 'RECEIVED_SHORT', 'CANCELLED')),
    CONSTRAINT uq_stock_transfers_number UNIQUE (client_id, number)
);

CREATE INDEX idx_stock_transfers_client_sent ON stock_transfers (client_id, sent_at DESC);
-- What is on the way to a branch: the receiving screen, and every product's "on the way" line.
CREATE INDEX idx_stock_transfers_open_to ON stock_transfers (to_branch_id)
    WHERE status IN ('ON_THE_WAY', 'PARTLY_RECEIVED');

CREATE TRIGGER trg_stock_transfers_set_updated_at
    BEFORE UPDATE ON stock_transfers
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- One product on a waybill. Quantities are in the product's stock unit; the line is accounted for
-- when received + missing + damaged reaches sent. What is on the way is the rest - derived, never
-- stored as stock (D-B8).
CREATE TABLE stock_transfer_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id       UUID          NOT NULL REFERENCES stock_transfers (id) ON DELETE CASCADE,
    product_id        UUID          NOT NULL REFERENCES products (id),
    quantity_sent     INTEGER       NOT NULL CHECK (quantity_sent > 0),
    quantity_received INTEGER       NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    quantity_missing  INTEGER       NOT NULL DEFAULT 0 CHECK (quantity_missing >= 0),
    quantity_damaged  INTEGER       NOT NULL DEFAULT 0 CHECK (quantity_damaged >= 0),
    -- As typed ("10 crates"), for the waybill. Display only, like stock_movements.entered_* (V21).
    entered_unit      VARCHAR(32),
    entered_quantity  NUMERIC(14, 3),
    -- The TRANSFER_OUT that took it off the sending branch's shelf.
    out_movement_id   UUID          NOT NULL REFERENCES stock_movements (id),
    CONSTRAINT uq_stock_transfer_lines_product UNIQUE (transfer_id, product_id),
    CONSTRAINT chk_stock_transfer_lines_accounted
        CHECK (quantity_received + quantity_missing + quantity_damaged <= quantity_sent)
);

CREATE INDEX idx_stock_transfer_lines_product ON stock_transfer_lines (product_id);

-- Each time stock off a waybill is received. The id is made on the phone, so a receipt resent from a
-- phone that was offline is recorded once, and two people receiving the same waybill can't both.
CREATE TABLE stock_transfer_receipts (
    id          UUID PRIMARY KEY,
    transfer_id UUID        NOT NULL REFERENCES stock_transfers (id) ON DELETE CASCADE,
    received_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_transfer_receipts_transfer ON stock_transfer_receipts (transfer_id);

-- A ledger row that is part of a transfer, and - on a TRANSFER_IN - the delivery its stock came from
-- (D-B5): the new lot carries that delivery's cost and supplier, and the recall trace walks through.
ALTER TABLE stock_movements ADD COLUMN transfer_id UUID REFERENCES stock_transfers (id);
ALTER TABLE stock_movements ADD COLUMN source_lot_id UUID REFERENCES stock_movements (id);
CREATE INDEX idx_stock_movements_transfer_id ON stock_movements (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX idx_stock_movements_source_lot_id ON stock_movements (source_lot_id) WHERE source_lot_id IS NOT NULL;

-- SEND_STOCK (D-U4): subdivides STOCK_OUT, V27 style. Every role that can issue stock today can send
-- it, so nobody loses anything; an owner can take it away from a role that sells but shouldn't move
-- stock.
INSERT INTO permissions (code, description) VALUES
    ('SEND_STOCK', 'Send stock to another branch')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions so ON so.id = rp.permission_id AND so.code = 'STOCK_OUT'
JOIN permissions p ON p.code = 'SEND_STOCK'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- The two transfer notifications (UX section 5.13): on the way, to the receiving branch; received short,
-- to the sending branch and the owners.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT'));

-- What is on the way to a branch is on every product's branch figures (BranchFigure.onTheWay), so a
-- line changing is a change for the on-device catalogue - closing a waybill as missing moves no
-- branch_stock row, but the figure changes (V52's pattern).
CREATE OR REPLACE FUNCTION stock_transfer_lines_log_change() RETURNS TRIGGER AS $$
BEGIN
    PERFORM log_product_change(t.client_id, NEW.product_id) FROM stock_transfers t WHERE t.id = NEW.transfer_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_transfer_lines_log_change
    AFTER INSERT OR UPDATE ON stock_transfer_lines
    FOR EACH ROW EXECUTE FUNCTION stock_transfer_lines_log_change();

-- Cancelling changes no line, only the waybill's status: every line's product is a change then.
CREATE OR REPLACE FUNCTION stock_transfers_log_status_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        PERFORM log_product_change(NEW.client_id, l.product_id) FROM stock_transfer_lines l WHERE l.transfer_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_transfers_log_status_change
    AFTER UPDATE ON stock_transfers
    FOR EACH ROW EXECUTE FUNCTION stock_transfers_log_status_change();
