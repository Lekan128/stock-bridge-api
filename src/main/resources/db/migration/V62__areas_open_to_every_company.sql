-- Areas are free, so every company may add them itself (owner, 2026-10-30): there is no per-company
-- pilot for support to switch on. V61's flag goes; AREAS_ROLLOUT=off remains the server-wide switch.
ALTER TABLE clients DROP COLUMN areas_enabled;
