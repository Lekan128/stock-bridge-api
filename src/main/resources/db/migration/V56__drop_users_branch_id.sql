-- Multiple branches, Phase 4 (MULTI_BRANCH_BUILD_PLAN.md section 1): users.branch_id (V6) said "nothing
-- filters on this column yet", and nothing ever did. Who works where is user_branches (V52). Any value
-- still there is carried over first, so no assignment is lost, then the column goes.
INSERT INTO user_branches (client_id, user_id, branch_id)
SELECT u.client_id, u.id, u.branch_id
FROM users u
JOIN branches b ON b.id = u.branch_id AND b.client_id = u.client_id
WHERE u.branch_id IS NOT NULL
ON CONFLICT (user_id, branch_id) DO NOTHING;

DROP INDEX IF EXISTS idx_users_branch_id;
ALTER TABLE users DROP COLUMN branch_id;
