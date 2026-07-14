-- Refresh token storage. Polymorphic across subject types (tenant user or
-- super admin) - no FK on subject_id since it can point at either users.id or
-- super_admins.id. Only the hash is stored, never the raw token, so a DB leak
-- doesn't hand out usable tokens.

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type VARCHAR(20) NOT NULL,
    subject_id   UUID NOT NULL,
    token_hash   VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT chk_refresh_tokens_subject_type CHECK (subject_type IN ('USER', 'SUPER_ADMIN'))
);

-- Supports revoke-all-for-subject lookups (e.g. force logout).
CREATE INDEX idx_refresh_tokens_subject ON refresh_tokens(subject_type, subject_id);
