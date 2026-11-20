-- Quick mode for every job (owner, 2026-11-19, claude.ai/artifact/GZhSg4fmatbg6DgRfnCzHP): the manager chooses where a
-- person starts - the usual app or quick mode - and can set their quick mode buttons, which the person can also change.
-- Tables and Tickets join quick mode's buttons (still up to four).
ALTER TABLE users ADD COLUMN starts_in_quick BOOLEAN NOT NULL DEFAULT FALSE;
