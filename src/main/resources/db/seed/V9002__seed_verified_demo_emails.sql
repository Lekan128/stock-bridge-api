-- Marks the seeded demo users as email-verified.
--
-- WHY THIS IS A NEW FILE RATHER THAN AN EDIT TO V9000/V9001
-- Those two have already run on every existing local and docker database, and
-- Flyway validates an applied migration by checksum. Editing either would fail
-- startup for every developer whose database predates the change, with an error
-- about a checksum mismatch that has nothing to do with what they were doing.
-- New file, new version, no checksum touched.
--
-- WHY IT IS NEEDED AT ALL
-- V8 added users.is_email_verified with DEFAULT FALSE and backfilled TRUE for the
-- rows that existed when it ran. That grandfathering is correct for real
-- deployments, but it makes the demo data behave differently depending on the age
-- of the database it landed in:
--
--   * A database seeded BEFORE V8 (any developer who was already running the
--     project) had its demo users grandfathered to TRUE, and email works.
--   * A FRESH database - `docker compose down -v`, a new machine, CI - applies
--     V1..V10 first and only then the V9000-series seeds, so the demo users are
--     inserted after the column exists and take its DEFAULT of FALSE.
--
-- The second case is the trap: every gate in the email package is working exactly
-- as designed, and the symptom is simply that no mail is ever sent to the demo
-- accounts. Nothing errors, nothing is logged as a failure, and APP_TOUR.md's
-- five-minute walkthrough quietly produces no order receipt. Two developers
-- following the same instructions get different results based on when they first
-- ran the project, which is the worst kind of difference to debug.
--
-- Demo data exists to be immediately usable, so it is verified. This file is in
-- db/seed and therefore never runs in production - see application-prod.yml,
-- which loads only classpath:db/migration.
UPDATE users
SET is_email_verified = TRUE,
    -- created_at, not now(), for the reason V8 gives: it keeps a grandfathered
    -- row distinguishable from one a human actually confirmed, which always has
    -- email_verified_at strictly later than created_at.
    email_verified_at = created_at
WHERE is_email_verified = FALSE;
