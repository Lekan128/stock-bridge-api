-- A made-here item's ingredients follow it to every branch it is for (owner, 2026-11-17 and 2026-11-21): an
-- ingredient only has to be for the branch, never in stock. MadeWithService.set did this when the ingredients were
-- saved, but an item reaching a branch any other way (Branches -> Add products, the new-branch flow, "All branches"
-- and a branch added later) left its ingredients behind. ProductBranchService now does it on every path; this mends,
-- once, what is already there (BRANCH_PRICES_PLAN.md section 9.1, step 0). No triggers: the rule lives in the code.
-- Each row added logs a product change (V92), so phones hear of it on their next sync.

-- Every ingredient, for every branch its item is for.
INSERT INTO product_branches (product_id, branch_id, client_id)
SELECT DISTINCT pi.ingredient_id, pb.branch_id, pi.client_id
FROM product_ingredients pi
JOIN products ing ON ing.id = pi.ingredient_id AND NOT ing.for_all_branches
JOIN product_branches pb ON pb.product_id = pi.product_id
ON CONFLICT DO NOTHING;

-- Every ingredient of an item for all branches, for every open branch.
INSERT INTO product_branches (product_id, branch_id, client_id)
SELECT DISTINCT pi.ingredient_id, b.id, pi.client_id
FROM product_ingredients pi
JOIN products item ON item.id = pi.product_id AND item.for_all_branches
JOIN products ing ON ing.id = pi.ingredient_id AND NOT ing.for_all_branches
JOIN branches b ON b.client_id = pi.client_id AND b.is_active
ON CONFLICT DO NOTHING;
