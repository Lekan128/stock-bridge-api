-- Per-recipient email eligibility: three columns on users that decide whether a
-- given address is allowed to receive a given kind of mail.
--
-- Until now every address ProcurePal held was assumed reachable and assumed
-- willing. Neither is safe at any volume: SES scores a sending domain on its
-- bounce and complaint rates, and an account that drifts over the threshold is
-- suspended - which takes down order receipts, payment confirmations and account
-- mail together, for every tenant at once. is_email_verified is the proactive half
-- of the defence (only mail an address someone has proved they can read);
-- receive_promotional_email is the consent half. The reactive half - an SNS
-- webhook that flips both flags off when SES reports a hard bounce or a complaint
-- - lands in a later migration and reads these same columns.
--
-- WHY THREE COLUMNS AND NOT ONE
-- email_verified_at is not decoration. "When did they verify" answers questions a
-- boolean cannot: whether a verification predates a change of address, how long a
-- re-verification campaign has been running, and which rows were grandfathered by
-- this file rather than verified by a human. It is modelled exactly like
-- notifications.read_at, for the reason stated on that column - the timestamp is
-- strictly more useful than the boolean and costs nothing. The boolean is kept
-- alongside it rather than derived from NULL-ness because it is read on the hot
-- path of every send and a NOT NULL boolean indexes and reads more plainly than
-- an IS NOT NULL over a nullable timestamp.
--
-- ============================================================================
-- THE BACKFILL, AND WHY THE STATEMENT ORDER BELOW IS THE ENTIRE POINT
-- ============================================================================
-- Existing rows are GRANDFATHERED AS VERIFIED. New rows default to UNVERIFIED.
-- Those are two different answers to the same column, and getting both requires
-- doing them in this order:
--
--   1. ADD COLUMN ... NOT NULL DEFAULT FALSE  - this both fixes FALSE as the
--      default for every future INSERT and writes FALSE into every existing row.
--   2. UPDATE users SET ...                   - this flips only the rows that
--      exist right now, and touches the column default not at all.
--
-- Doing it the other way round (add with DEFAULT TRUE, then try to walk it back
-- with ALTER COLUMN SET DEFAULT FALSE) reaches the same end state on a database
-- that applies this file cleanly and a silently wrong one on any database where
-- the migration is interrupted between the two statements - it would leave every
-- subsequent signup verified without ever having verified anything, which is the
-- exact failure this feature exists to prevent, installed by the feature itself.
-- The order below fails safe at every intermediate point.
--
-- Why grandfather at all, rather than start everyone at FALSE and let them
-- verify: because FALSE is enforced from the moment this deploys, and every
-- address in the live database would become ineligible simultaneously. Order
-- receipts, fulfilment updates and payment confirmations would all go dark for
-- every existing customer, and the only mail that could still reach them would
-- be the verification request - a mass re-verification campaign nobody asked for,
-- sent to an entire customer base at once, which is itself exactly the traffic
-- pattern that gets a sending domain flagged. The cost of grandfathering is that
-- these rows carry a verified flag that no human ever confirmed; that is
-- acceptable because they are addresses the system has already been mailing
-- successfully, and the SNS bounce/complaint webhook will demote the ones that
-- turn out to be dead. Grandfathering trusts history; it does not invent trust.
--
-- receive_promotional_email is the opposite shape and needs no UPDATE at all:
-- DEFAULT TRUE backfills every existing row to TRUE as part of the ADD COLUMN,
-- and TRUE is also the correct default for future rows. That is deliberate and
-- consistent with the unsubscribe design - this is an opt-OUT model, so consent
-- is presumed and withdrawal is one click (RFC 8058). Note this flag is not
-- sufficient on its own: promotional mail requires verified AND opted-in, so a
-- grandfathered row that never verifies gets no marketing either.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN is_email_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at         TIMESTAMPTZ,
    ADD COLUMN receive_promotional_email BOOLEAN NOT NULL DEFAULT TRUE;

-- Step 2. Only rows that existed before this migration are affected; the column
-- default set above still governs everything inserted after it.
--
-- email_verified_at is set to created_at rather than now(): now() would claim
-- these addresses were verified at deploy time, which is a fact that never
-- happened and would make a "verified in the last N days" query answer nonsense
-- for the entire pre-existing customer base. created_at says the truthful thing -
-- this address has been trusted since the account was made - and leaves the
-- grandfathered rows identifiable, since a genuinely verified row will always
-- have email_verified_at strictly later than created_at.
UPDATE users
SET is_email_verified = TRUE,
    email_verified_at = created_at
WHERE is_email_verified = FALSE;

-- ============================================================================
-- LOOKUP INDEXES
--
-- Eligibility is resolved by ADDRESS, not by id: the sender holds a string and
-- has to find the row (if any) that owns it. That lookup runs once per recipient
-- per email, inside the business transaction that triggered the send, so it is on
-- the latency path of checkout - it must not be a sequential scan over users.
--
-- Two separate indexes rather than one because the predicate is an OR across two
-- columns (see below), and no single btree can serve that; Postgres will BitmapOr
-- the two. Both are on lower(...) because addresses are compared
-- case-insensitively - a plain index on the column would simply not be used by a
-- lower(email) = ? predicate.
--
-- WHY username IS INDEXED TOO, AND NOT JUST email
-- A tenant's first user signs up with an email address AS their username (see
-- ClientSignupService), and users.email is nullable and frequently never filled
-- in. For a large fraction of real rows the only address on the row is the
-- username. Matching on email alone would classify those users as "no such
-- address" and refuse them every transactional email they have been receiving
-- since signup. EmailRecipients.forUser already falls back the same way, for the
-- same reason.
-- ============================================================================
CREATE INDEX idx_users_lower_email ON users (lower(email));
CREATE INDEX idx_users_lower_username ON users (lower(username));

-- ============================================================================
-- clients.admin_contact_email: WHY IT GETS AN INDEX AND NOTHING ELSE
--
-- This is the seam the whole feature turns on. Most email this application sends
-- does not go to a users row at all - it goes to clients.admin_contact_email,
-- which is the company's address of record and has no verification flag, no
-- consent flag, and nobody to log in and click a link. Deciding what to do with
-- it is a policy question, and the policy is stated in full in the EmailEligibility
-- javadoc rather than here, because it is code that enforces it.
--
-- The schema decision, though, belongs here: this column gets NO new columns of
-- its own. An is_verified/verified_at pair on clients was considered and rejected
-- for now, because a flag with no flow to set it is worse than no flag - it would
-- be FALSE forever on every row, and either be ignored (dead schema) or take every
-- company's order mail dark (the thing the grandfathering above exists to
-- prevent). The rule EmailEligibility implements instead needs only a lookup:
-- "does any client claim this address", answered case-insensitively, hence the
-- functional index and nothing more.
--
-- If a later module does build a flow to verify a company contact address - a
-- confirmation link sent to it and clicked - that is when clients earns
-- admin_contact_email_verified_at, in that module's own migration, and this
-- comment is the note that the decision was deferred rather than missed.
-- ============================================================================
CREATE INDEX idx_clients_lower_admin_contact_email ON clients (lower(admin_contact_email));
