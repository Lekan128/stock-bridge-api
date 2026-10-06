-- Self-service password reset by email (PASSWORD_RESET_PLAN.md).
--
-- Same shape as email_verification_tokens (V9) and deliberately NOT the same
-- table. A stolen verification link marks an address reachable that its thief
-- already reads; a stolen reset link is an account takeover. So these tokens
-- live one hour rather than a day, and keeping them apart means no future
-- change to verification's expiry or redemption rules can silently loosen
-- this one. See the class doc on AccountEmails.
--
-- Only the SHA-256 of the token is stored, so a read of this table (a backup,
-- a log of a query) cannot be turned into a working link.
CREATE TABLE password_reset_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The address the link was mailed to, lowercased. A snapshot: if the
    -- account's address changes afterwards the link stops working, because it
    -- was proof of control over the OLD inbox.
    email_address VARCHAR(255) NOT NULL,
    token_hash    VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    consumed_at   TIMESTAMPTZ,
    -- Set when a newer link is requested for the same user: only the newest
    -- link ever works.
    superseded_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT chk_password_reset_tokens_address_lower CHECK (email_address = lower(email_address))
);

-- Every request supersedes the user's outstanding tokens, so user_id is looked
-- up on each write.
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
