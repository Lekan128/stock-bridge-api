-- Purchase orders: whether the supplier has been told, as one fact on the order
-- (PURCHASE_ORDER_PLAN.md, redesign of 2026-11-04).
--
-- Until now "was it sent?" was worked out from three places (the latest email, whatsapp_opened_at,
-- message_copied_at), which is fine for one order but cannot be filtered or counted for a list.
-- So the order records the latest revision the supplier was given, when, and how:
--
--   sent_revision  the revision the supplier has; below `revision` means a change has not gone out
--   sent_at        when that happened
--   sent_via       EMAIL, WHATSAPP, MESSAGE (copied), or PHONE (ordered by phone, nothing sent)
--
-- WhatsApp and a copied message are what the buyer did, not proof of delivery; the screens keep
-- saying "You opened WhatsApp", never "delivered".
ALTER TABLE expected_deliveries
    ADD COLUMN sent_revision INTEGER,
    ADD COLUMN sent_at       TIMESTAMPTZ,
    ADD COLUMN sent_via      VARCHAR(16);

-- Orders already shared by hand.
UPDATE expected_deliveries
SET sent_revision = revision,
    sent_at       = GREATEST(whatsapp_opened_at, message_copied_at),
    sent_via      = CASE WHEN whatsapp_opened_at IS NOT NULL
                              AND (message_copied_at IS NULL OR whatsapp_opened_at >= message_copied_at)
                         THEN 'WHATSAPP' ELSE 'MESSAGE' END
WHERE po_number IS NOT NULL
  AND (whatsapp_opened_at IS NOT NULL OR message_copied_at IS NOT NULL);

-- Orders emailed (the latest successful email wins over a WhatsApp tap made before it).
UPDATE expected_deliveries e
SET sent_revision = e.revision,
    sent_at       = sent.created_at,
    sent_via      = 'EMAIL'
FROM (SELECT expected_delivery_id, max(created_at) AS created_at
      FROM purchase_order_emails
      WHERE status = 'SENT'
      GROUP BY expected_delivery_id) sent
WHERE sent.expected_delivery_id = e.id
  AND (e.sent_at IS NULL OR sent.created_at >= e.sent_at);

-- No new index: the list's filters ride on idx_expected_deliveries_client_status (V33).
