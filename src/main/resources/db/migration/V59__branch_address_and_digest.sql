-- Multiple branches, closing the plans' gaps (MULTI_BRANCH_UX_PLAN.md sections 5.1, 5.11, 5.13).

-- "Add address and phone" when adding a branch, and editable after (section 5.11).
ALTER TABLE branches ADD COLUMN address VARCHAR(300);

-- The owner's daily digest of every branch, only for those who turn it on (section 5.13). One email
-- an evening at most: branch_digest_sent_on is claimed before sending, so two servers can't both send.
ALTER TABLE users ADD COLUMN branch_digest BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN branch_digest_sent_on DATE;
