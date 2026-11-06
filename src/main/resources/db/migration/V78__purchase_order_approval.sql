-- Approving purchase orders (PURCHASE_ORDER_PLAN.md section 11.5, owner 2026-11-06): "There should be
-- privilege to be able to send the purchase order and there should another to create it for approval.
-- Someone with send can create and also approve, but someone that can only create for approval will
-- have to wait for it to be approved."
--
--   CREATE_PURCHASE_ORDER  make orders (and price requests); an order goes for approval before it is sent
--   SEND_PURCHASE_ORDER    all of that, plus approve, send, change a sent order, call it off
--
-- V27 style: both subdivide MANAGE_INVENTORY, which is what every purchase order action asked for until
-- now, so every role that holds it today - custom roles too - gets both and nobody loses anything. An
-- owner takes SEND_PURCHASE_ORDER away from a role that should only ask.
INSERT INTO permissions (code, description) VALUES
    ('CREATE_PURCHASE_ORDER', 'Make purchase orders for someone to approve'),
    ('SEND_PURCHASE_ORDER',   'Approve and send purchase orders to suppliers')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions mi ON mi.id = rp.permission_id AND mi.code = 'MANAGE_INVENTORY'
JOIN permissions p ON p.code IN ('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Where an order stands on approval. NULL is "no approval involved" - every order made so far, and every
-- order made by someone who can send. WAITING: sent for approval. CHANGE_ASKED: the approver asked for a
-- change, with a note. APPROVED: approved, and from then on it is sent like any other order.
ALTER TABLE expected_deliveries
    ADD COLUMN approval          VARCHAR(16),
    ADD COLUMN approval_asked_by UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN approval_asked_at TIMESTAMPTZ,
    ADD COLUMN approval_note     VARCHAR(500),
    ADD COLUMN approved_by       UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN approved_at       TIMESTAMPTZ,
    ADD COLUMN change_asked_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN change_asked_at   TIMESTAMPTZ,
    ADD COLUMN change_note       VARCHAR(500),
    ADD CONSTRAINT chk_expected_deliveries_approval
        CHECK (approval IS NULL OR approval IN ('WAITING', 'CHANGE_ASKED', 'APPROVED'));

-- "To approve" is a chip with a count on every list load; only open orders waiting are ever counted.
CREATE INDEX idx_expected_deliveries_waiting_approval
    ON expected_deliveries (client_id) WHERE approval = 'WAITING';

-- To the people who can approve: an order is waiting for them. To whoever made it: approved, or a change asked.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT', 'STOCK_REQUEST', 'STOCK_REQUEST_ANSWERED', 'AREA_RETURN',
    'PO_APPROVAL_WAITING', 'PO_APPROVAL_ANSWERED'));
