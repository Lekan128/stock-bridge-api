-- Areas, Part 4 (STOCK_AREAS_PART_4.md): areas that confirm what arrives (D-A2, D-A9).
--
-- areas.confirms, area_moves.status WAITING / CONFIRMED_SHORT / CANCELLED, confirmed_by / confirmed_at and
-- area_move_lines.quantity_confirmed / quantity_damaged were laid down by V60 and V63. A move to an area that
-- confirms takes its lines off the sending area at once and holds them, in no area's figure but still in the
-- branch's, until the area confirms (or the move is cancelled): branch = sum of areas + what waits (D-E6).

-- Moved to an area that confirmed at the time: its lines waited. Settled = confirmed or cancelled; until then
-- status is WAITING. The count's carry-forward reads the hold at moved_at and the rest at the settling time.
ALTER TABLE area_moves ADD COLUMN waits        BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE area_moves ADD COLUMN cancelled_by UUID        REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE area_moves ADD COLUMN cancelled_at TIMESTAMPTZ;

-- "Used straight away" (Part 3.5) on a line that waits: what arrives is recorded as used when it's confirmed.
ALTER TABLE area_move_lines ADD COLUMN used_now BOOLEAN NOT NULL DEFAULT FALSE;

-- Waiting for an area to confirm (to its people), and confirmed short (to the mover and the owners).
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT'));
