-- The team's queue of setup requests (LANDING_PAGE_PLAN.md, step 4).
--
-- Speed to lead is the plan's rule 5: every request answered on WhatsApp within 5 minutes in
-- staffed hours. contacted_at is when somebody first moved a request out of NEW, so the minutes
-- from created_at to contacted_at are the speed-to-lead figure, measured rather than remembered.
ALTER TABLE setup_requests
    ADD COLUMN contacted_at TIMESTAMPTZ,
    -- The team's own note: "sent list on Tuesday", "two branches, call back Friday".
    ADD COLUMN note         VARCHAR(500),
    ADD COLUMN updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

-- The queue is read by status, oldest first.
CREATE INDEX idx_setup_requests_status_created_at ON setup_requests (status, created_at);

-- A shop may now sign up with its WhatsApp number and no email (LANDING_PAGE_PLAN.md §4: the email
-- is asked for later, for receipts). V11's rule was that a COMPANY must have a contact of record so
-- it is never unreachable; the phone now counts as one. Mail to a company with no address simply
-- has no recipient (EmailRecipients.forClient), as for any other client without one.
ALTER TABLE clients
    DROP CONSTRAINT chk_clients_company_has_contact_email,
    ADD CONSTRAINT chk_clients_company_has_contact
        CHECK (client_type <> 'COMPANY' OR admin_contact_email IS NOT NULL OR phone IS NOT NULL);
