-- Multiple branches, Phase 3 (MULTI_BRANCH_UX_PLAN.md section 5.8, "Phones"): what each phone last said
-- it had waiting to send, so the owner's branch table can say a branch's figure is behind ("3 waiting")
-- instead of showing a stale number as true (Pattern C.2). One row per person per phone, replaced each
-- time the phone asks the catalogue what changed. Nothing about the writes themselves: only how many.
CREATE TABLE phone_sync_status (
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Made on the phone once and kept in its storage; not a hardware identifier.
    device_id         UUID         NOT NULL,
    client_id         UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- The phone's working branch when it last reported; null in "All branches".
    branch_id         UUID         REFERENCES branches (id) ON DELETE SET NULL,
    waiting_count     INTEGER      NOT NULL DEFAULT 0 CHECK (waiting_count >= 0),
    needs_you_count   INTEGER      NOT NULL DEFAULT 0 CHECK (needs_you_count >= 0),
    oldest_waiting_at TIMESTAMPTZ,
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

CREATE INDEX idx_phone_sync_status_branch ON phone_sync_status (client_id, branch_id);
