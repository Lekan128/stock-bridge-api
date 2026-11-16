-- Selling till Step 3 (SELLING_TILL_PLAN.md, owner 2026-11-16): cancelling a sale is never erased.
--
-- A cancelled sale keeps its lines (each cancelled by a compensating row, as before) and now says why, who
-- cancelled it, and - when a cashier asked after the first two minutes - who asked and who answered. Undo no
-- longer deletes a sale.

-- A sale from before payments has no payment, but can still be cancelled with a reason.
ALTER TABLE sales ALTER COLUMN payment_method DROP NOT NULL;

ALTER TABLE sales
    -- Why: picked from four pictures at the counter.
    ADD COLUMN cancel_reason       VARCHAR(16) CHECK (cancel_reason IN ('WRONG_ITEM', 'WRONG_AMOUNT', 'CHANGED_MIND', 'OTHER')),
    ADD COLUMN cancel_note         VARCHAR(300),
    -- REQUESTED: a cashier asked, waiting for someone who can cancel sales. DECLINED: they said no.
    -- CANCELLED: the whole sale was cancelled (by its cashier in the first two minutes, or by a manager).
    ADD COLUMN cancel_state        VARCHAR(12) CHECK (cancel_state IN ('REQUESTED', 'DECLINED', 'CANCELLED')),
    ADD COLUMN cancel_requested_by UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    -- Who cancelled it, or declined the request, and when.
    ADD COLUMN cancel_decided_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN cancel_decided_at   TIMESTAMPTZ;

CREATE INDEX idx_sales_cancel_requested ON sales (client_id) WHERE cancel_state = 'REQUESTED';

-- CANCEL_SALES: cancel any sale at any time, and approve or decline a cashier's request. Every role that
-- manages inventory - which could cancel any sale until now - keeps that, so nobody loses anything.
INSERT INTO permissions (code, description) VALUES
    ('CANCEL_SALES', 'Cancel any sale, and approve or decline when someone asks to cancel one')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions mi ON mi.id = rp.permission_id AND mi.code = 'MANAGE_INVENTORY'
JOIN permissions p ON p.code = 'CANCEL_SALES'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Asking to cancel (to those who can cancel sales) and the answer (to the cashier who asked).
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT', 'STOCK_REQUEST', 'STOCK_REQUEST_ANSWERED', 'AREA_RETURN',
    'PO_APPROVAL_WAITING', 'PO_APPROVAL_ANSWERED', 'PRICE_REPLY', 'SALE_CANCEL_WAITING', 'SALE_CANCEL_ANSWERED'));
