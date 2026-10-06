-- Which token replaced this one when it was rotated (RefreshTokenService.rotate).
--
-- Refresh tokens are single-use: every refresh retires the presented token and issues a new
-- one. When the reply carrying the new token is lost (the signal drops, the app is closed
-- mid-request) the client keeps the retired token, and before this column the next refresh was
-- refused - a logout caused by nothing but a bad connection. It was also hit by two tabs
-- restoring the same session at once.
--
-- With the link, a retired token is accepted again ONLY while the token that replaced it is
-- still unused: proof the client never received it. Once the replacement has itself been used,
-- or the session is logged out, the old token is refused exactly as before.
--
-- NULL for a token that was never rotated, and for one retired by logout - which is what keeps a
-- logged-out token dead.
ALTER TABLE refresh_tokens
    ADD COLUMN replaced_by_id UUID REFERENCES refresh_tokens (id) ON DELETE SET NULL;
