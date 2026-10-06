-- Founding setup requests (LANDING_PAGE_PLAN.md, steps 3 and 4).
--
-- The landing page's one action asks for two things, a business name and a WhatsApp number, and
-- that alone books a founding setup: the team messages the shop, loads their product list and
-- does a first count with them. This row is that lead. It exists before any account does (the
-- password comes after), so it is deliberately NOT tenant-scoped: there is no clients row yet.
-- client_id is filled in when the same shop creates its account.
--
-- The founding offer's live numbers ("63 of 100 setups left", "4 of 10 booked this week") are
-- counts over this table, never typed into the page.
CREATE TABLE setup_requests (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name      VARCHAR(120) NOT NULL,
    -- E.164, normalised by the API (+2348012345678).
    whatsapp           VARCHAR(16)  NOT NULL,
    -- Which page the request came from: landing, founding, pricing.
    source             VARCHAR(30)  NOT NULL DEFAULT 'landing',
    -- Where the setup has got to. NOT_A_FIT frees the founding place it took.
    status             VARCHAR(20)  NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'CONTACTED', 'LIST_RECEIVED', 'LOADED', 'RUNNING', 'NOT_A_FIT')),
    -- Whether it took one of the founding places. A request made once they are gone (or after
    -- the deadline) is still a lead, on the regular plan.
    counts_as_founding BOOLEAN      NOT NULL DEFAULT TRUE,
    client_id          UUID         REFERENCES clients (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_setup_requests_created_at ON setup_requests (created_at);
CREATE INDEX idx_setup_requests_whatsapp ON setup_requests (whatsapp);
