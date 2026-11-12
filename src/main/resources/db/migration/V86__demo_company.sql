-- A shared demo company on production for prospects to try (owner, 2026-11-11). One account, the owner
-- (admin@procurepaddy.com) as its root user, prospects added as users with their own emails.
--
-- is_demo marks it. Everything that would reach the outside world from it is held back on that flag
-- (supplier emails, marketplace orders, payments), SuperAdmin's platform numbers leave it out, and only a
-- demo company can be wiped and refilled by SuperAdmin's "Reset demo".
ALTER TABLE clients
    ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;

-- Who added this user. In a demo company one prospect may manage only the users they added themselves, so
-- nobody can lock another prospect out. Null: added before this column, or the account holder at signup.
ALTER TABLE users
    ADD COLUMN created_by UUID REFERENCES users (id) ON DELETE SET NULL;
