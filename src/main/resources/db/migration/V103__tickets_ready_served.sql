-- Selling till Stage C, Group C after the owner's review (2026-11-19, claude.ai/artifact/9knT5eyUVVW2H7xrCDGfUX).
--
-- Tickets are one switch per branch, off to start. A ticket is Ready when its area says so, and Served when a waiter
-- takes it to the table (Served counts as Ready). An item taken off before it was made shows on the ticket it was on,
-- as "Don't send", until someone in the area taps OK. An area can be one nothing is sold from (a store room).
ALTER TABLE branches ADD COLUMN tickets_on BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE areas ADD COLUMN sells BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tickets RENAME COLUMN done_at TO ready_at;
ALTER TABLE tickets RENAME COLUMN done_by TO ready_by;
ALTER TABLE tickets ADD COLUMN served_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN served_by UUID REFERENCES users (id) ON DELETE SET NULL;

-- "Don't send" seen: an OK in the area clears the warning, and the item leaves the ticket.
ALTER TABLE ticket_lines ADD COLUMN cleared_at TIMESTAMPTZ;

-- Separate "don't make" tickets are no longer made; any from the first build are let go.
DELETE FROM tickets WHERE kind = 'DONT_MAKE';
