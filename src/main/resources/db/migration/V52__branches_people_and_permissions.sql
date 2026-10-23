-- Multiple branches, Phase 1 (MULTI_BRANCH_BUILD_PLAN.md section 8.1): what Phase 0 left out because
-- nothing read it yet - who works where, the branch permissions, a branch's phone and closing date,
-- the branch an expected delivery is going to and an import is for, and the change feed's view of
-- branch figures. Branch codes (WB-BOL-0042) wait for Phase 2's waybills.

ALTER TABLE branches ADD COLUMN phone VARCHAR(50);
-- When it was closed; is_active stays the switch. Closing keeps every row that names the branch.
ALTER TABLE branches ADD COLUMN closed_at TIMESTAMPTZ;

-- The pilot gate (MULTI_BRANCH_ROLLOUT, like SELLING_ROLLOUT): in pilot only companies support has
-- enabled may add a second branch. Nothing else reads it - a company that already has two keeps them.
ALTER TABLE clients ADD COLUMN branches_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Who works where (D-B9, MULTI_BRANCH_UX_PLAN.md section 5.10). A person with no row works at the main
-- branch: one-branch companies never write any, and adding a second branch writes one for everybody
-- (BranchService), so nobody's branch moves if the main branch later changes. A role with
-- WORK_AT_ALL_BRANCHES ignores this table.
CREATE TABLE user_branches (
    client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    branch_id UUID NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, branch_id)
);

CREATE INDEX idx_user_branches_branch_id ON user_branches (branch_id);
CREATE INDEX idx_user_branches_client_id ON user_branches (client_id);

-- Branch permissions (D-U3) and the selling-price permission (MULTI_BRANCH_UX_PLAN.md section 5.14),
-- V27 style. VIEW_ALL_BRANCHES (V6) keeps its meaning, read-only; its description now says so.
INSERT INTO permissions (code, description) VALUES
    ('WORK_AT_ALL_BRANCHES', 'Record stock at every branch, including ones added later'),
    ('MANAGE_BRANCHES', 'Add, rename and close branches'),
    ('CHANGE_SELLING_PRICES', 'Set and change selling prices, which apply at every branch')
ON CONFLICT (code) DO NOTHING;

UPDATE permissions SET description = 'See every branch''s stock, read-only' WHERE code = 'VIEW_ALL_BRANCHES';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('OWNER', 'WORK_AT_ALL_BRANCHES'),
    ('OWNER', 'MANAGE_BRANCHES')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name AND r.client_id IS NULL
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- CHANGE_SELLING_PRICES subdivides MANAGE_PRODUCTS: every role holding that today, system or custom,
-- gets it, so nobody loses a capability; a company can then take it away from branch staff.
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions mp ON mp.id = rp.permission_id AND mp.code = 'MANAGE_PRODUCTS'
JOIN permissions p ON p.code = 'CHANGE_SELLING_PRICES'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- The branch an expected delivery is going to. Receiving it is a stock-in, which names its own branch;
-- this decides whose "Needs you today" shows it.
ALTER TABLE expected_deliveries ADD COLUMN branch_id UUID REFERENCES branches (id);
UPDATE expected_deliveries e
SET branch_id = b.id
FROM branches b
WHERE b.client_id = e.client_id AND b.is_default AND e.branch_id IS NULL;
ALTER TABLE expected_deliveries ALTER COLUMN branch_id SET NOT NULL;
CREATE INDEX idx_expected_deliveries_branch_id ON expected_deliveries (branch_id);

-- The branch a stock sheet is for. Null on a catalogue-only import, and on sessions from before this.
ALTER TABLE import_sessions ADD COLUMN branch_id UUID REFERENCES branches (id);

-- The on-device catalogue now carries each branch's figure, so a branch figure changing is a change
-- to report (V37's pattern). The row names the product; the feed reads its branch figures when it
-- answers.
CREATE OR REPLACE FUNCTION branch_stock_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM log_product_change(OLD.client_id, OLD.product_id);
        RETURN OLD;
    END IF;
    PERFORM log_product_change(NEW.client_id, NEW.product_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_branch_stock_log_change
    AFTER INSERT OR UPDATE OR DELETE ON branch_stock
    FOR EACH ROW EXECUTE FUNCTION branch_stock_log_change();
