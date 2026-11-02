-- Emailing a purchase order to its supplier (PURCHASE_ORDER_PLAN.md phase 3).
--
-- One row per attempt, so the order can say "Emailed to tony@... just now" or "Didn't reach
-- tony@..." a week later, a resend is a new row rather than an overwrite, and the sending caps are
-- a count rather than a counter that can drift.
CREATE TABLE purchase_order_emails (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id            UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    expected_delivery_id UUID NOT NULL REFERENCES expected_deliveries (id) ON DELETE CASCADE,
    -- Which version of the order went out (plan 4.4).
    revision             INTEGER NOT NULL DEFAULT 1,
    -- Where it was sent: the supplier's address at the moment of sending, not a live join, so a
    -- later correction to the supplier cannot rewrite what happened.
    to_address           VARCHAR(255),
    -- SENT: the provider took it.  FAILED: it never left (see problem).
    -- BOUNCED / COMPLAINED: the provider reported back afterwards (Resend webhook).
    status               VARCHAR(12) NOT NULL,
    -- A plain sentence the order page can show as it is. Null when nothing went wrong.
    problem              VARCHAR(300),
    -- The provider's id for the message, which is what a bounce webhook names.
    provider_message_id  VARCHAR(100),
    created_by           UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_purchase_order_emails_status
        CHECK (status IN ('SENT', 'FAILED', 'BOUNCED', 'COMPLAINED'))
);

-- The order page asks for the latest attempts of one order.
CREATE INDEX idx_purchase_order_emails_order ON purchase_order_emails (expected_delivery_id, created_at DESC);
-- A bounce webhook finds its row by the provider's id.
CREATE INDEX idx_purchase_order_emails_provider_id ON purchase_order_emails (provider_message_id)
    WHERE provider_message_id IS NOT NULL;
-- The sending caps: this company's, and this address's, in the last day.
CREATE INDEX idx_purchase_order_emails_company_day ON purchase_order_emails (client_id, created_at DESC);
CREATE INDEX idx_purchase_order_emails_address_day ON purchase_order_emails (to_address, created_at DESC);

-- "Email the order to Tony" starts on for a supplier once an email to them has gone through, and
-- goes back to off if it bounces or the address is changed (plan 4.3a). Off to begin with, always.
ALTER TABLE company_vendors
    ADD COLUMN email_orders BOOLEAN NOT NULL DEFAULT FALSE;
