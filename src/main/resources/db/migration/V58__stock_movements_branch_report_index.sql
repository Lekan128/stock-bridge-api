-- Multiple branches, Phase 4 (MULTI_BRANCH_BUILD_PLAN.md section 9): every report at one branch -
-- dashboard totals, the movements list and its summary, sales, the losses report - reads one company's
-- movements at one branch over a date range. With separate client/date and branch indexes Postgres
-- combined the two: 163 ms for one total at 26 branches x 2M movements, a second for the dashboard.
CREATE INDEX IF NOT EXISTS idx_stock_movements_client_branch_occurred
    ON stock_movements (client_id, branch_id, occurred_at);
