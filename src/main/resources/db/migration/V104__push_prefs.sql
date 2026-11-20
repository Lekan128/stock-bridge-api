-- Selling till Stage C, Group C (owner, 2026-11-19): each person's own notifications, per kind, on their own phone.
-- TICKETS_NEW: new tickets for the areas they work in. TICKETS_READY: what they sent to a table is ready.
-- No row: the default - on for staff, off for people who record everywhere (managers, the owner).
CREATE TABLE push_prefs (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind    VARCHAR(30) NOT NULL CHECK (kind IN ('TICKETS_NEW', 'TICKETS_READY')),
    is_on   BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, kind)
);
