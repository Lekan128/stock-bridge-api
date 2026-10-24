-- Waybill codes (WB-BOL-0042) are given when a branch is created, not on its first send (V53 did that).
-- A send that wrote the code held the branch row while it locked products, and a sale at that branch -
-- whose movement row checks the branch - held a product and waited for the branch: a deadlock (found by
-- the 50-way test in Phase 4). Every way a branch is made - signup, Add a branch, seeds - gets one here.

CREATE OR REPLACE FUNCTION branch_code_for(p_client_id UUID, p_name TEXT) RETURNS TEXT AS $$
DECLARE
    base TEXT := upper(left(regexp_replace(coalesce(p_name, ''), '[^A-Za-z]', '', 'g'), 3));
    candidate TEXT;
    n INTEGER := 1;
BEGIN
    IF base = '' THEN
        base := 'BR';
    END IF;
    candidate := base;
    -- Letters only in the base, so "BOL2" can only ever be the second Bolade, never another base.
    WHILE EXISTS (SELECT 1 FROM branches WHERE client_id = p_client_id AND code = candidate) LOOP
        n := n + 1;
        candidate := base || n;
    END LOOP;
    RETURN candidate;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION branches_assign_code() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.code IS NULL THEN
        NEW.code := branch_code_for(NEW.client_id, NEW.name);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_branches_assign_code
    BEFORE INSERT ON branches
    FOR EACH ROW EXECUTE FUNCTION branches_assign_code();

-- Every branch without one, oldest first in each company, so a company's first Bolade is BOL.
DO $$
DECLARE
    b RECORD;
BEGIN
    FOR b IN SELECT id, client_id, name FROM branches WHERE code IS NULL ORDER BY client_id, created_at, id LOOP
        UPDATE branches SET code = branch_code_for(b.client_id, b.name) WHERE id = b.id;
    END LOOP;
END;
$$;
