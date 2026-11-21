-- "To check" on the small line on Products (BRANCH_PRICES_PLAN.md section 9.2, step 3): things the app did at a branch
-- that someone there should look at once - an ingredient added to a branch because an item made with it went there.
-- Cleared by "Looks right" or by editing the product; cleared rows stay as the record. "To fix" (no area where an item
-- is made, no price at a branch) is worked out live and never stored, so it can't go out of date.

CREATE TABLE branch_product_checks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id        UUID        NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    product_id       UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    reason           VARCHAR(32) NOT NULL CHECK (reason IN ('ADDED_AS_INGREDIENT')),
    -- The item it was added for ("Added for Jollof rice").
    about_product_id UUID REFERENCES products (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_at       TIMESTAMPTZ,
    checked_by       UUID
);

-- One open check per product, branch and reason.
CREATE UNIQUE INDEX uq_branch_product_checks_open ON branch_product_checks (branch_id, product_id, reason) WHERE checked_at IS NULL;
CREATE INDEX idx_branch_product_checks_client_open ON branch_product_checks (client_id) WHERE checked_at IS NULL;
