-- Sharing a purchase order (PURCHASE_ORDER_PLAN.md phase 5).
--
-- share_token is the unguessable part of the link a supplier opens without logging in
-- (procurepaddy.com/o/<token>); it is what makes a WhatsApp message able to carry the order, since
-- WhatsApp links cannot attach a file. The PDF behind it is drawn on the fly and never stored.
--
-- The two timestamps are the honest half of the WhatsApp and Copy lines on the order: all we can
-- know is that the buyer opened WhatsApp or copied the message, never that anything was delivered
-- or read, so that is all they record.
ALTER TABLE expected_deliveries
    ADD COLUMN share_token        VARCHAR(64),
    ADD COLUMN whatsapp_opened_at TIMESTAMPTZ,
    ADD COLUMN message_copied_at  TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_expected_deliveries_share_token
    ON expected_deliveries (share_token)
    WHERE share_token IS NOT NULL;
