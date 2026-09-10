-- Single-use, expiring tokens that let a person PROVE they can read the address
-- on their users row, which is the only way is_email_verified (V8) ever becomes
-- TRUE for a row this application created.
--
-- V8 shipped the flag and the enforcement; this file ships the flow. Without it
-- every user created after V8 is permanently ineligible for transactional mail -
-- the column defaults to FALSE, nothing sets it, and EmailEligibility refuses
-- every order receipt for the account forever. That is why this table is not an
-- enhancement of V8 but the other half of it.
--
-- ============================================================================
-- WHY THE RAW TOKEN IS NOT IN THIS TABLE
-- ============================================================================
-- token_hash holds SHA-256 of an opaque 64-byte random value that is generated,
-- emailed, and then discarded. Exactly the shape refresh_tokens (V3) uses, and
-- deliberately so: this is a bearer credential of the same class. Anyone holding
-- the raw value can flip a verified flag on somebody else's account, so a dump of
-- this table - a backup on a laptop, a read replica, a screenshot of a support
-- query - must not hand out working links. Hashing costs nothing here because the
-- lookup is always BY the token: the redeeming request supplies the raw value,
-- the application hashes it, and the unique index below turns that into a single
-- index probe. There is no query that needs to read a token back out.
--
-- No salt and no bcrypt, which is a deviation from how passwords are stored and
-- is correct: these are 512 bits of CSPRNG output, not human-chosen secrets.
-- There is no dictionary to attack and nothing to rainbow-table, so a slow KDF
-- would only make redemption slow. Same reasoning as RefreshTokenService.
--
-- ============================================================================
-- WHY THIS TABLE IS NOT TENANT-SCOPED
-- ============================================================================
-- There is no client_id column and this is not an oversight. The row is redeemed
-- by POST /api/email/verify, which is permit-all: no principal, so
-- TenantResolutionFilter leaves TenantContext empty and Hibernate's tenant filter
-- disabled for the whole request. A tenant-scoped entity could not be read there
-- at all (TenantScopedRepository.findByIdForCurrentTenant throws on a null
-- context) and a client_id column would only invite a future reader to make it
-- one, which would break the endpoint the table exists to serve.
--
-- Tenancy is not lost - it is reachable through user_id, which is where it
-- belongs. What the token authorises is a change to exactly one users row, named
-- by this row, and the raw token is the entire authorisation. Nothing here is
-- ever listed, searched or aggregated per tenant.
--
-- ON DELETE CASCADE because an outstanding token for a deleted user is a link
-- that resolves to nothing; letting the FK clean it up is strictly better than
-- discovering the orphan when somebody clicks it.
--
-- ============================================================================
-- WHY THE ADDRESS IS STORED ON THE TOKEN
-- ============================================================================
-- email_address records the address this token was actually MAILED TO, lowercased
-- at write time to match how EmailEligibility and EmailMessage normalise. It is
-- not redundant with users.email.
--
-- The token is a claim about an inbox, not about a user. If a token is issued to
-- ada@old.example, and the user then edits their profile to ben@new.example, the
-- outstanding link would - bound to user_id alone - verify the NEW address on
-- evidence that only ever concerned the old one. Anyone who can change a profile
-- field could therefore self-verify any address they like by requesting a link to
-- one inbox and switching to another before clicking. Comparing this column
-- against the user's current address at redemption time closes that, and the cost
-- is one real, benign case: a user who changes their address while a link is in
-- flight must request a new one. EmailVerificationService says the same thing at
-- greater length.
--
-- ============================================================================
-- WHY THERE ARE TWO "DEAD" TIMESTAMPS AND NO DELETE
-- ============================================================================
-- consumed_at   - somebody clicked this link and it worked.
-- superseded_at - a newer token was issued for this user, so this one was retired
--                 without ever being clicked.
--
-- Neither is a DELETE, because "was this link already used?" has to stay
-- answerable. Deleting on redemption makes a replayed click indistinguishable
-- from a forged one, and the support question is always "I clicked it and it says
-- invalid" - which has two very different answers depending on which happened.
--
-- Keeping them apart rather than folding both into one invalidated_at is what
-- makes that answer precise: consumed means the account IS verified and the user
-- can simply sign in, superseded means a newer mail is sitting in their inbox and
-- they clicked the older one. Same distinction refresh_tokens.revoked_at is too
-- coarse to draw, and it costs one nullable column.
--
-- A token is redeemable only while all four conditions hold: it exists,
-- consumed_at IS NULL, superseded_at IS NULL, and expires_at is in the future.
-- That is enforced in EmailVerificationService and deliberately NOT in a CHECK
-- constraint - the conditions are read at redemption time, not write time, and a
-- constraint could not express "now()" without being non-immutable.
--
-- Rows are never pruned by this application. They are small, bounded by signups
-- plus resends (which are rate-limited), and they are the audit trail. If volume
-- ever justifies it, deleting rows whose expires_at is older than a few months is
-- safe; deleting live ones is not.

CREATE TABLE email_verification_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Lowercased by the application before it gets here. Not a FK to anything -
    -- it is a snapshot of where the mail went, and it must stay unchanged even
    -- if the users row moves on. That is the whole point of it.
    email_address VARCHAR(255) NOT NULL,
    token_hash    VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    consumed_at   TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_verification_tokens_token_hash UNIQUE (token_hash)
);

-- Every issue supersedes this user's outstanding tokens, and every resend counts
-- what has recently been issued to them, so user_id is looked up on both write
-- paths. Without this index both degrade to a sequential scan over a table that
-- grows with every signup on the platform.
CREATE INDEX idx_email_verification_tokens_user ON email_verification_tokens (user_id);
