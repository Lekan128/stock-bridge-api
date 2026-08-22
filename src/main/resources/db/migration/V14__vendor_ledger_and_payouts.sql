-- Commission, escrow, settlement: the money half of the marketplace.
--
-- ============================================================================
-- WHAT V11 PROMISED AND THIS FILE DELIVERS
-- ============================================================================
-- V11 stamped clients.commission_rate and order_items.commission_rate and then
-- said, in as many words, "columns now, engine later": the rate had to be frozen
-- from the very first vendor sale or that sale could never be settled correctly
-- afterwards, but the ledger, the payout batch and the accrual job belonged to a
-- later module. This is that module.
--
-- V11 also explained why there is deliberately no order_items.commission_amount:
-- commission ACCRUES ON DELIVERY, not at checkout and not at collection, so the
-- amount is a consequence of an EVENT rather than a property of a line, and it
-- belongs somewhere a return can post a reversing entry against it. That
-- somewhere is vendor_ledger_entries below.
--
-- ============================================================================
-- THE STAKEHOLDER'S FOUR DECISIONS, AND WHERE EACH ONE LANDS IN THIS SCHEMA
-- ============================================================================
--   1. Commission accrues on DELIVERY, not at collection. Monnify sub-account
--      splitting was considered and rejected (VENDOR_RESEARCH.md Section A,
--      "Split-at-collection vs hold-then-payout"): splitting the charge pays a
--      vendor before delivery is confirmed and before returns are known.
--      -> vendor_ledger_entries rows are written by the accrual, and nothing
--         writes them at checkout or at payment.
--
--   2. Funds are held in ESCROW until delivery is fully confirmed - "so we don't
--      pay them for what has not been delivered".
--      -> Escrow is a STATE, not a table. See the escrow section below.
--
--   3. Settlement runs in BATCHES, BIWEEKLY.
--      -> vendor_payout_batches, one row per vendor per fortnight. The cadence
--         is defined precisely under period_start / period_end.
--
--   4. A vendor can GENERATE A STATEMENT of accumulated fees and what they will
--      be paid.
--      -> Everything a statement needs is derivable from these three tables by
--         query. Nothing is denormalised onto the vendor, and no statement is
--         stored: a stored statement is a fourth place for money to live.
--
-- ============================================================================
-- WHY THE LEDGER IS APPEND-ONLY, AND WHAT THAT COSTS
-- ============================================================================
-- A ledger row is a claim about money that a vendor may quote back at us months
-- later. If a row can be edited, then "what did we tell them in March" has no
-- answer, and every dispute becomes our word against theirs with no evidence on
-- either side. So: no UPDATE, no DELETE, ever. A correction is a NEW row with the
-- opposite sign that names the row it corrects (reverses_entry_id).
--
-- That rule is enforced by a trigger at the bottom of this file rather than by
-- convention, for exactly the reason V13 enforced the one-user-per-vendor rule
-- with a trigger: an absence of code that updates the table is not the same thing
-- as the table being un-updatable, and the routes that would break it are the
-- ones nobody has written yet - a data fix, a future admin screen, an ORM
-- lifecycle callback somebody adds to a base class.
--
-- The cost is real and is accepted: a typo in a memo is permanent, a mistaken
-- accrual is corrected by reversal rather than by deletion, and the table only
-- grows. All three are better than a money table with an UPDATE path.
--
-- ============================================================================
-- ESCROW IS A STATE, NOT A TABLE
-- ============================================================================
-- There is no escrow_balances table and there must never be one: a stored balance
-- is a number that can disagree with the rows it was computed from, and the first
-- time it does, nobody can tell which one is wrong. Every escrow question is
-- answered by a predicate over the rows below.
--
--   PENDING  - the buyer has paid, the goods have not been confirmed delivered.
--              NOT IN THIS LEDGER AT ALL. The platform is holding the buyer's
--              money and the vendor has not earned it, so there is no entry to
--              make. Derived from orders: a vendor's orders that are paid and
--              have no accrual yet. This is the "still at risk" figure.
--
--   HELD     - accrued (delivery confirmed) and not yet settled by a PAID batch.
--              This is what the platform owes the vendor RIGHT NOW. It is
--              SUM(amount) over that vendor's entries, and it is the closing
--              balance on their statement.
--              Sub-divides into money no batch has claimed yet ("payable at the
--              next run") and money a PENDING batch has claimed but not yet
--              disbursed ("in flight") - the difference being a membership row in
--              vendor_payout_batch_lines.
--
--   SETTLED  - claimed by a batch that an operator marked PAID. The money left
--              the bank; a PAYOUT entry records it and drives the balance back
--              down. Nothing is deleted to represent this.
--
-- "What do we owe this vendor right now" is SUM(amount) over their entries.
-- "What is still at risk" is the PENDING figure, which lives in orders.
-- Both are one query and neither is stored.
--
-- ============================================================================
-- NOT TENANT-SCOPED, AND THAT IS NOT AN OVERSIGHT
-- ============================================================================
-- None of these three tables carries client_id and none is a TenantAwareEntity.
-- They are keyed by seller_client_id, which is the same arrangement payments and
-- payment_webhook_events have and for a related reason: the rows are written from
-- threads that belong to somebody else (the accrual fires while the BUYER's
-- tenant filter is enabled, and the escrow sweep runs on a scheduler thread with
-- no tenant at all), so a filter keyed on the reader would be wrong at write time
-- and would have to be re-pointed on every path.
--
-- What keeps one vendor out of another's money is therefore the SAME thing that
-- keeps vendor sales analytics apart, and it is an explicit predicate, not a
-- filter: every read binds a seller id that came from VendorGuard.requireSeller()
-- and never from a request parameter. See VendorLedgerService.
-- ============================================================================


-- ============================================================================
-- vendor_payout_batches: one vendor, one fortnight, one payable amount.
-- ============================================================================
-- Created first because vendor_ledger_entries references it (a PAYOUT entry names
-- the batch that produced it).
--
-- WHY A BATCH IS PER-VENDOR AND NOT PER-RUN
-- A "payout run" pays several vendors, but a vendor is paid by ONE bank transfer
-- for ONE amount, and that transfer is the thing an operator reconciles, a vendor
-- queries, and a statement quotes. A run that produced a single row covering six
-- vendors would have no place to hang the transfer reference, the paid/failed
-- state or the failure reason, because those are per-vendor facts: one vendor's
-- transfer can bounce on a bad account number while the other five land. So a run
-- creates N of these, and "the run" is just the set of batches sharing a
-- period_end.
CREATE TABLE vendor_payout_batches (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Whose money. RESTRICT, not CASCADE: deleting a vendor account must not
    -- silently delete the record that we paid them, and in practice this makes
    -- the delete fail, which is the correct outcome for an account with a
    -- settlement history.
    seller_client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE RESTRICT,
    -- Human-readable and quotable on the phone or in an email: 'PB-2026-000042'.
    -- Same shape and same reasoning as orders.order_number - gaps are tolerated,
    -- because it is an identifier a human reads out, not an audited count.
    batch_number      VARCHAR(40) NOT NULL,

    -- ------------------------------------------------------------------------
    -- THE BIWEEKLY CADENCE, DEFINED EXACTLY
    -- ------------------------------------------------------------------------
    -- "Biweekly" is ambiguous in English and must not be ambiguous in a money
    -- table, so here is the whole rule:
    --
    --   Payout periods are fixed 14-day windows anchored to
    --   MONDAY 1 JANUARY 2024, 00:00 Africa/Lagos.
    --   Period n is [anchor + 14n days, anchor + 14(n+1) days).
    --
    -- Africa/Lagos, spelled out, NOT UTC and not the server's default zone. The
    -- business is in Nigeria; WAT is UTC+01:00 and observes no daylight saving,
    -- so the offset is constant and a fortnight boundary is a real, stable
    -- Nigerian midnight rather than 01:00 local. Defaulting to UTC here would put
    -- every cutoff an hour early in local terms, which is invisible until a
    -- delivery confirmed at 00:30 WAT on cutoff day lands in the wrong fortnight
    -- and a vendor's statement disagrees with their own records by one order.
    -- The column is TIMESTAMPTZ, so what is stored is an absolute instant; the
    -- zone matters when the instant is CHOSEN, which is PayoutCadence's job.
    --
    -- period_end is the CUTOFF and is EXCLUSIVE. period_start is descriptive - it
    -- names which fortnight this run closes and makes the row readable - but it
    -- is deliberately NOT the eligibility predicate. Eligibility is
    -- "unsettled AND occurred_at < period_end", with no lower bound, so an entry
    -- that missed its own fortnight (it was in a batch that failed, or the
    -- operator skipped a run) is picked up by the next one instead of being
    -- stranded forever. A ledger where money can fall between two windows is
    -- worse than one where a window occasionally reaches back.
    period_start      TIMESTAMPTZ NOT NULL,
    period_end        TIMESTAMPTZ NOT NULL,

    -- PENDING -> PAID, or PENDING -> FAILED. There is no automated disbursement
    -- here and this module deliberately calls no external payment API: a human
    -- makes the transfer and a human records that they made it. See
    -- settled_by/settled_at.
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Hard-coded NGN everywhere, as VENDOR_RESEARCH Section D directs: multi
    -- currency contaminates every money calculation in a ledger. The column
    -- exists so the assumption is visible rather than implied.
    currency          VARCHAR(3) NOT NULL DEFAULT 'NGN',

    -- The three movement totals and the net, frozen at run time. These are
    -- DERIVABLE from the batch's lines and are stored anyway, which is the one
    -- denormalisation in this file and needs its reason stated: a batch is a
    -- document an operator acts on and a vendor is told about, and the amount on
    -- that document must not change if somebody later posts a correction dated
    -- inside the window. Freezing them is the same argument order_items uses for
    -- snapshotting unit_price. They are cross-checked against the lines by the
    -- statement, so a disagreement is detectable rather than silent.
    proceeds_total    NUMERIC(14, 2) NOT NULL,
    commission_total  NUMERIC(14, 2) NOT NULL,
    reversal_total    NUMERIC(14, 2) NOT NULL,
    net_amount        NUMERIC(14, 2) NOT NULL,
    line_count        INTEGER NOT NULL,

    -- Who ran it and when. A super_admins row, not a users row: running a payout
    -- is a platform-operator action, and super admins are an entirely separate
    -- identity from tenant users (V1). SET NULL so an operator leaving the
    -- company does not delete the record of the run they made.
    run_by            UUID REFERENCES super_admins (id) ON DELETE SET NULL,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Who marked the transfer done, when, and against what bank reference. This
    -- is the whole audit trail for "we paid them": there is no gateway callback
    -- to corroborate it, so the human and the reference ARE the evidence.
    settled_by        UUID REFERENCES super_admins (id) ON DELETE SET NULL,
    settled_at        TIMESTAMPTZ,
    payment_reference VARCHAR(100),
    -- Required on FAILED. A batch that failed with no stated reason is
    -- indistinguishable from one somebody clicked by accident, and its lines are
    -- about to be released back into the next run.
    failure_reason    VARCHAR(500),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_vendor_payout_batches_status
        CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    CONSTRAINT chk_vendor_payout_batches_period
        CHECK (period_end > period_start),
    -- Strictly positive. A batch is an instruction to transfer money, and there
    -- is no such thing as an instruction to transfer nothing. It also encodes the
    -- clawback rule from VENDOR_RESEARCH Section A ("Refund clawback / negative
    -- balance"): a vendor whose reversals outweigh their new sales has a NEGATIVE
    -- eligible balance, and the correct response is to create no batch at all and
    -- let the debt net off against their next sales - not to create a batch for a
    -- negative amount that somebody might try to pay.
    CONSTRAINT chk_vendor_payout_batches_net_positive
        CHECK (net_amount > 0),
    CONSTRAINT chk_vendor_payout_batches_line_count
        CHECK (line_count > 0),
    -- PAID and "somebody recorded paying it" are the same fact stated twice, and
    -- the pair must never come apart - a PAID batch with no human on it is
    -- exactly the audit hole this table exists to close.
    CONSTRAINT chk_vendor_payout_batches_settled_shape
        CHECK ((status = 'PAID') = (settled_at IS NOT NULL)),
    CONSTRAINT chk_vendor_payout_batches_failure_shape
        CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

CREATE UNIQUE INDEX uq_vendor_payout_batches_batch_number
    ON vendor_payout_batches (batch_number);

-- THE ANTI-DOUBLE-PAY GUARANTEE, HALF ONE OF TWO.
-- One live batch per vendor per cutoff. Re-running the same fortnight - an
-- operator clicking twice, two ops users racing, a retry after a timeout that
-- actually succeeded - collides here and fails loudly rather than producing a
-- second instruction to pay the same money. That is the house posture for
-- idempotency (see OrderNumberAllocator and CompanyVendorLinkService): the index
-- is the real guard, and a caller retrying is better than code that swallows a
-- constraint violation.
--
-- Partial, excluding FAILED, and that exclusion is the point: a failed transfer
-- must be re-runnable for the same period, and without WHERE it never could be.
CREATE UNIQUE INDEX uq_vendor_payout_batches_seller_period
    ON vendor_payout_batches (seller_client_id, period_end) WHERE status <> 'FAILED';

CREATE INDEX idx_vendor_payout_batches_seller_run_at
    ON vendor_payout_batches (seller_client_id, run_at DESC);
CREATE INDEX idx_vendor_payout_batches_status
    ON vendor_payout_batches (status);

COMMENT ON TABLE vendor_payout_batches IS
    'One vendor''s payable amount for one biweekly period. Marked PAID by a human - there is no '
    'automated disbursement. See V14__vendor_ledger_and_payouts.sql for the cadence definition.';
COMMENT ON COLUMN vendor_payout_batches.period_end IS
    'The EXCLUSIVE cutoff. Eligibility is "unsettled AND occurred_at < period_end" - period_start is '
    'descriptive only, so an entry that missed a run is picked up by the next one.';


-- ============================================================================
-- vendor_ledger_entries: every money event that ever happened to a vendor.
-- ============================================================================
-- Append-only (trigger at the bottom). One row per event per ORDER LINE, not per
-- order, because the commission rate is stamped per line (order_items.commission_
-- rate) and because a vendor checking a statement by hand needs to see the same
-- arithmetic they would do themselves: this many units at this price, times this
-- rate, equals this fee. An order-level row with a blended rate cannot be checked
-- by anybody.
--
-- SIGN CONVENTION, stated once and enforced by a CHECK
-- Amounts are signed FROM THE VENDOR'S POINT OF VIEW.
--   positive => the platform owes the vendor more
--   negative => the platform owes the vendor less
-- So SUM(amount) for a vendor IS what we owe them, with no CASE expression and no
-- per-type sign lookup anywhere in the application. Every balance in this module
-- is that one sum with a different WHERE clause.
--
-- ROUNDING, stated once and applied in exactly one place
-- Commission is a rate times an amount, so it is the only figure here that is not
-- simply copied from somewhere else. The rule:
--
--     commission = ROUND(line_total * commission_rate, 2), HALF UP, in NGN kobo.
--
-- Half-up to the kobo is the conventional choice for NGN and, more importantly,
-- it is what a person does with a calculator - which is the actual requirement,
-- because a vendor must be able to reproduce every figure on their statement by
-- hand. Each LINE is rounded independently and the rounded figures are summed;
-- the total is never re-derived by applying a rate to an order total, which would
-- differ by a kobo or two and make the statement fail to add up. The rule lives
-- in exactly one place in the application - VendorCommission.on(...) - and both
-- the accrual and the reversal call it.
--
-- The rate and the amount it was applied to are stored ON the commission row
-- (commission_rate, basis_amount) rather than being read back through the order
-- line, so a statement is self-contained arithmetic. That is not duplication for
-- its own sake: it is what lets a vendor check the row without us also having to
-- show them the order line, and it survives an order line being corrected.
CREATE TABLE vendor_ledger_entries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Whose ledger. RESTRICT for the same reason the batch does it.
    seller_client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE RESTRICT,

    -- The five kinds. Deliberately typed rather than "a signed amount with a
    -- memo": a statement groups by kind, a batch totals by kind, and a reversal
    -- has to be findable as a reversal. VENDOR_RESEARCH Section A lists shipping
    -- fee, penalty, marketing and adjustment as further kinds worth having and
    -- calls them "cheap now, painful to retrofit" - they are NOT added here,
    -- because a kind nothing posts is a kind nobody tested, and the CHECK below
    -- is a one-line migration to widen when a surface exists that writes one.
    --
    --   SALE_PROCEEDS       (+) the line total, owed to the vendor because the
    --                           goods were confirmed delivered.
    --   COMMISSION          (-) the platform's fee on that line.
    --   SALE_REVERSAL       (-) undoes a SALE_PROCEEDS after a refund, a return
    --                           or a cancellation. NOT a deletion - see the
    --                           append-only section at the top.
    --   COMMISSION_REVERSAL (+) undoes the matching COMMISSION. Always posted
    --                           WITH a SALE_REVERSAL and never alone: a refund
    --                           that reversed the sale but kept the fee would
    --                           charge a vendor for a sale that did not happen,
    --                           which VENDOR_RESEARCH Section C item 6 names as
    --                           the thing that breaks first.
    --   PAYOUT              (-) money that actually left the bank, posted when a
    --                           human marks a batch PAID and never before.
    entry_type        VARCHAR(30) NOT NULL,

    -- NUMERIC, never a float. 14,2 matches orders.total and order_items.line_total
    -- so nothing is ever widened or narrowed on its way in here.
    amount            NUMERIC(14, 2) NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'NGN',

    -- WHAT THIS ROW IS ABOUT, and why each reference is nullable.
    --
    -- order_id / order_item_id: NOT NULL for the four order-derived kinds and
    -- NULL for PAYOUT, enforced below rather than merely allowed. A proceeds row
    -- with no line is unauditable; a payout row pointing at one order is a lie,
    -- because a payout settles a fortnight of them. RESTRICT so an order that has
    -- money attached cannot be deleted out from under its ledger.
    order_id          UUID REFERENCES orders (id) ON DELETE RESTRICT,
    order_item_id     UUID REFERENCES order_items (id) ON DELETE RESTRICT,

    -- payout_batch_id: the exact inverse - NOT NULL on PAYOUT, NULL on
    -- everything else. Note what this column is NOT: it is not "the batch that
    -- settled this line". Settlement membership lives in
    -- vendor_payout_batch_lines, because recording it here would mean UPDATEing a
    -- ledger row, which this table does not permit.
    payout_batch_id   UUID REFERENCES vendor_payout_batches (id) ON DELETE RESTRICT,

    -- The row this row corrects. Present on exactly the two reversal kinds. This
    -- is what makes a correction auditable instead of merely opposite: without
    -- it, a -12,500.00 in March could be a reversal, a penalty or a typo, and
    -- only the memo would say which.
    reverses_entry_id UUID REFERENCES vendor_ledger_entries (id) ON DELETE RESTRICT,

    -- The arithmetic, carried on the row that used it. Present on exactly the two
    -- commission kinds. See the ROUNDING block above for why.
    commission_rate   NUMERIC(5, 4),
    basis_amount      NUMERIC(14, 2),

    -- WHEN THE MONEY EVENT HAPPENED, which is not when the row was written.
    -- Statements are ordered and windowed on this. Separate from created_at
    -- because the escrow sweep can post an accrual days after the delivery it is
    -- accruing for, and a statement that filed that entry under the sweep's run
    -- date would not reconcile against the vendor's own delivery records.
    occurred_at       TIMESTAMPTZ NOT NULL,

    -- Free text shown verbatim on the statement. The only human-readable
    -- explanation a vendor gets for a row, so reversals and payouts always carry
    -- one.
    memo              VARCHAR(500),

    -- THE ANTI-DOUBLE-POST GUARANTEE, HALF TWO OF TWO.
    -- A deterministic string derived from what the row is FOR - 'ACCRUAL:
    -- PROCEEDS:<orderItemId>', 'PAYOUT:<batchId>' and so on - so posting the same
    -- event twice collides on the unique index below instead of quietly doubling
    -- a vendor's balance.
    --
    -- Every trigger for a ledger write is repeatable: a delivery confirmation can
    -- race the escrow sweep, a refund can be clicked twice, a payout run can be
    -- retried after a timeout that actually committed. The services check first
    -- and return quietly on an already-posted event; a genuine simultaneous race
    -- gets here and fails loudly. That is deliberately the same posture
    -- CompanyVendorLinkService and OrderNumberAllocator take - the index is the
    -- real guard, and a caller retrying beats code that swallows a constraint
    -- violation.
    idempotency_key   VARCHAR(200) NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The human behind the row, when there was one: a super admin for a reversal
    -- or a payout, NULL for the automatic accrual (which has no human, and
    -- inventing one would be a false audit record). Deliberately NOT a foreign
    -- key: the actor can be a super_admins row or a users row depending on the
    -- path, and a column that can point at either table cannot be constrained to
    -- one of them. The memo names what happened; this names who, when anybody
    -- did.
    created_by        UUID,

    CONSTRAINT chk_vendor_ledger_entries_type
        CHECK (entry_type IN
               ('SALE_PROCEEDS', 'COMMISSION', 'SALE_REVERSAL', 'COMMISSION_REVERSAL', 'PAYOUT')),

    -- The sign convention, made unbreakable. A COMMISSION accidentally posted
    -- positive would pay a vendor their own fee, and would look entirely normal
    -- in every list and total until somebody reconciled a bank statement.
    --
    -- COMMISSION and COMMISSION_REVERSAL allow ZERO, and the two reasons are
    -- both real: a vendor onboarded commission-free has an agreed rate of exactly
    -- 0.0000 (V11 is explicit that this is a different fact from "no rate agreed",
    -- which is NULL and posts no row at all), and a genuinely tiny line can round
    -- to 0.00 at a real rate. Both should appear on the statement as a zero fee
    -- rather than vanish.
    CONSTRAINT chk_vendor_ledger_entries_sign
        CHECK ((entry_type = 'SALE_PROCEEDS' AND amount > 0)
            OR (entry_type = 'COMMISSION' AND amount <= 0)
            OR (entry_type = 'SALE_REVERSAL' AND amount < 0)
            OR (entry_type = 'COMMISSION_REVERSAL' AND amount >= 0)
            OR (entry_type = 'PAYOUT' AND amount < 0)),

    CONSTRAINT chk_vendor_ledger_entries_subject_shape
        CHECK ((entry_type = 'PAYOUT'
                    AND order_id IS NULL AND order_item_id IS NULL AND payout_batch_id IS NOT NULL)
            OR (entry_type <> 'PAYOUT'
                    AND order_id IS NOT NULL AND order_item_id IS NOT NULL AND payout_batch_id IS NULL)),

    CONSTRAINT chk_vendor_ledger_entries_reversal_shape
        CHECK ((entry_type IN ('SALE_REVERSAL', 'COMMISSION_REVERSAL')) = (reverses_entry_id IS NOT NULL)),

    -- Rate and basis travel together and belong to exactly the commission kinds.
    -- A commission row without its arithmetic is a number a vendor has to take on
    -- trust, which is the opposite of what this table is for.
    CONSTRAINT chk_vendor_ledger_entries_commission_shape
        CHECK ((entry_type IN ('COMMISSION', 'COMMISSION_REVERSAL'))
                   = (commission_rate IS NOT NULL AND basis_amount IS NOT NULL)),

    -- The same bound V11 put on clients.commission_rate and
    -- order_items.commission_rate, restated because this row is read on its own.
    CONSTRAINT chk_vendor_ledger_entries_rate_fraction
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1))
);

CREATE UNIQUE INDEX uq_vendor_ledger_entries_idempotency_key
    ON vendor_ledger_entries (idempotency_key);

-- The statement's own query, exactly: one vendor's rows in a date window,
-- in order. Also serves "what do we owe this vendor" (the same index, no window).
CREATE INDEX idx_vendor_ledger_entries_seller_occurred_at
    ON vendor_ledger_entries (seller_client_id, occurred_at);
-- "Has this order already accrued", and the reversal path's lookup of what to
-- reverse.
CREATE INDEX idx_vendor_ledger_entries_order_id
    ON vendor_ledger_entries (order_id);
CREATE INDEX idx_vendor_ledger_entries_payout_batch_id
    ON vendor_ledger_entries (payout_batch_id);

COMMENT ON TABLE vendor_ledger_entries IS
    'Append-only ledger of every money event per vendor. Never UPDATE or DELETE - a correction is a '
    'new, opposite-signed row naming the original in reverses_entry_id. Enforced by trigger.';
COMMENT ON COLUMN vendor_ledger_entries.amount IS
    'Signed from the VENDOR''s point of view: positive means the platform owes them more. '
    'SUM(amount) per seller is what we owe them, with no CASE expression anywhere.';


-- ============================================================================
-- vendor_payout_batch_lines: which ledger rows a batch claimed.
-- ============================================================================
-- WHY THIS TABLE EXISTS AT ALL, WHEN A COLUMN WOULD HAVE DONE
-- The obvious design is vendor_ledger_entries.settled_batch_id, set when a batch
-- picks a line up. That design requires UPDATEing a ledger row, and this ledger
-- does not permit that. Membership is not a financial fact about the entry - it
-- is a CLAIM a batch makes on it - so it lives on its own and the entry is never
-- touched.
--
-- The separation earns its keep the first time a transfer bounces. Marking a
-- batch FAILED deletes its membership rows, which releases the lines back into
-- the next run. Nothing in the ledger changes, because nothing about the money
-- changed: no payment was made, so no PAYOUT entry was ever posted. Deleting rows
-- HERE is legitimate for exactly that reason, and it is the only deletion this
-- module performs anywhere.
--
-- THE ANTI-DOUBLE-PAY GUARANTEE, TIGHTENED TO THE LINE
-- uq below makes it structurally impossible for one ledger entry to be claimed by
-- two live batches. The per-period index on the batch table stops a whole run
-- being repeated; this one stops a single line being paid twice by two runs that
-- looked at overlapping windows. Both are needed, and neither is a substitute for
-- the other.
CREATE TABLE vendor_payout_batch_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE, uniquely in this module: the membership rows have no meaning
    -- without their batch, and (unlike everything else here) deleting them
    -- destroys no financial record. The batch is the document; this is its index.
    payout_batch_id UUID NOT NULL REFERENCES vendor_payout_batches (id) ON DELETE CASCADE,
    -- RESTRICT: a ledger entry cannot be deleted at all, so this merely says so
    -- twice.
    ledger_entry_id UUID NOT NULL REFERENCES vendor_ledger_entries (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_vendor_payout_batch_lines_ledger_entry
    ON vendor_payout_batch_lines (ledger_entry_id);
CREATE INDEX idx_vendor_payout_batch_lines_batch
    ON vendor_payout_batch_lines (payout_batch_id);

COMMENT ON TABLE vendor_payout_batch_lines IS
    'Which ledger entries a batch claimed. Separate from the ledger because recording it there would '
    'mean UPDATEing an append-only row. Deleted when a batch is marked FAILED, which releases the '
    'lines back into the next run.';


-- ============================================================================
-- The append-only rule, enforced where every route passes.
-- ============================================================================
-- V13 made "a vendor holds at most one user" a trigger rather than an absence of
-- permission, on the grounds that hiding a capability is not the same as making
-- the rule true, and that the routes which would break it are the ones nobody has
-- written yet. Exactly the same argument applies here, with money instead of
-- logins at stake: a future admin screen, a data fix run at 2am, a Hibernate
-- @PreUpdate somebody adds to a shared base class, or an ORM dirty-check on an
-- entity somebody made mutable would each rewrite history silently.
--
-- THE MAINTENANCE ESCAPE, AND WHY IT IS A SESSION SETTING
-- A genuinely append-only table with no escape cannot be cleaned up - which
-- matters for exactly one caller, the integration suite, whose local Postgres is
-- shared and never reset, and would otherwise accumulate one run's fixtures into
-- the next run's balances. Rather than pretend that need away, it is named:
--
--     SET LOCAL app.ledger_maintenance = 'on';
--
-- Nothing in the APPLICATION sets this, and nothing should - grep for it and the
-- only hits are test fixtures and, one day, a hand-written data fix somebody
-- consciously decided to run. That is the point of routing the exception through
-- a setting instead of through a permission or a service method: it cannot be
-- reached by accident from a request, and it leaves the decision visible in the
-- script that made it.
CREATE OR REPLACE FUNCTION vendor_ledger_entries_append_only() RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('app.ledger_maintenance', TRUE) = 'on' THEN
        RETURN COALESCE(OLD, NEW);
    END IF;

    RAISE EXCEPTION
        'vendor_ledger_entries is append-only (attempted %). Post a new, opposite-signed row that '
        'names the original in reverses_entry_id instead.', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_vendor_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON vendor_ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION vendor_ledger_entries_append_only();
