-- ============================================================================
-- Reactive deliverability: the suppression list, and the audit log of the SES
-- bounce/complaint notifications that write to it.
--
-- V8 built the PROACTIVE half of email eligibility - three flags on users that
-- say whether somebody has proved they can read an address and whether they
-- still want marketing. This file builds the REACTIVE half: what happens when
-- the mail provider itself tells us an address is dead or that its owner has
-- reported us as spam.
--
-- ============================================================================
-- WHY A TABLE KEYED BY THE ADDRESS, AND NOT MORE FLAGS ON users
-- ============================================================================
-- V8's own comment records the gap it left open, and this file is the answer to
-- it. The verified/consent flags live on users. But the majority of mail this
-- application sends does not go to a users row at all - it goes to
-- clients.admin_contact_email, the company's address of record, which routinely
-- belongs to a shared finance or operations inbox that no user has ever logged
-- in as. When SES reports that such an address is permanently undeliverable
-- there is no user row to demote. Flipping user flags would report success and
-- change nothing, and we would keep mailing a dead address forever - which is
-- precisely the behaviour that costs a sending domain its reputation.
--
-- Three shapes were available:
--
--   1. Add is_suppressed / suppressed_at to clients. Rejected. It fixes exactly
--      one of the three address sources and leaves the other two (a user's
--      address, and app.email.operator-address, which is a config value with no
--      row in any table at all) with nowhere to record the same fact. It also
--      repeats V8's own rejected idea of putting deliverability state on a table
--      whose rows are about something else.
--   2. Add the columns to BOTH users and clients. Rejected harder: the same fact
--      would then have two homes that can disagree, and every read would have to
--      consult both and decide which wins.
--   3. A standalone list keyed by the lowercased address string. Chosen. The
--      address is the only thing all three sources have in common, and it is
--      also the only thing SES tells us - a bounce notification carries an
--      address, never an id of ours. Keying on what the provider actually says
--      means no lookup can fail to find the row it should have found.
--
-- The cost is honest and worth stating: this table has no foreign key to
-- anything, so nothing in the database enforces that a suppressed address
-- corresponds to a real user or client. That is not a defect, it is the
-- requirement - an address can be suppressed that belongs to no row we own, and
-- it must stay suppressed if the user or client that once held it is deleted.
-- A suppression is a fact about an inbox, not about an account.
--
-- ============================================================================
-- WHY SUPPRESSION IS ITS OWN TABLE AND NOT A ROW STATE ON ses_notification_events
-- ============================================================================
-- The two tables below look similar and are not. ses_notification_events is an
-- append-only log of things that were said to us; email_suppressions is the
-- current decision. Deriving the decision from the log at read time would put an
-- aggregate over an unbounded, provider-controlled table on the latency path of
-- every single send, and would make un-suppressing an address require either
-- deleting history or inventing a tombstone event. One row per address, deleted
-- when the suppression is lifted, is both faster to read and simpler to reason
-- about.
-- ============================================================================

CREATE TABLE email_suppressions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The inbox, lowercased and trimmed by the writer. VARCHAR(320) is the RFC
    -- 5321 maximum (64 local + @ + 255 domain); users.username and
    -- clients.admin_contact_email are both VARCHAR(255), so this is deliberately
    -- WIDER than either of them. A bounce for an address we could never have
    -- stored still has to be recordable - the notification is evidence about an
    -- inbox regardless of whether any row of ours could hold it.
    address             VARCHAR(320) NOT NULL,

    -- No CHECK constraint and no enum type, for the same reason payments.provider
    -- has neither: adding a reason (a manual block, a list-unsubscribe complaint,
    -- a future provider's vocabulary) must not require a migration, and a
    -- notification carrying a reason we do not recognise must never be the thing
    -- that fails the transaction. The application enum
    -- (EmailSuppressionReason) is the real vocabulary; this column is its
    -- storage.
    reason              VARCHAR(40) NOT NULL,

    -- The SNS MessageId, or SES's own feedbackId, of the notification that caused
    -- this. Nullable because a manual suppression has no notification behind it.
    -- This is the join back to ses_notification_events for "why is this address
    -- suppressed" - deliberately by value rather than by foreign key, so that
    -- pruning the event log (which grows with provider traffic, not with our
    -- data) can never cascade into deleting a live suppression.
    source_message_id   VARCHAR(200),

    -- SES's diagnosticCode - the remote SMTP server's own words, e.g.
    -- "smtp; 550 5.1.1 user unknown". The single most useful field when a
    -- customer asks why they stopped receiving mail, and the only field that
    -- distinguishes "this mailbox never existed" from "this domain no longer
    -- accepts mail from us". TEXT, not VARCHAR: it is foreign free text and
    -- truncating it would destroy exactly the detail it exists to carry.
    diagnostic          TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One row per address, and this constraint is load-bearing rather than
    -- tidiness. It is what makes suppression idempotent at the storage layer: a
    -- provider that sends the same hard bounce four times, or two notifications
    -- naming the same recipient, cannot produce four rows that later disagree
    -- about the reason. The writer upserts against this constraint.
    CONSTRAINT uq_email_suppressions_address UNIQUE (address)
);

-- The unique constraint above already creates a btree on address, and that is
-- the index the read path uses: EmailEligibility asks "is this exact lowercased
-- string suppressed" once per recipient per message, inside the business
-- transaction that triggered the send. Note there is deliberately NO functional
-- lower() index here, unlike V8's indexes on users and clients - those exist
-- because those columns store whatever case the user typed. This column is
-- normalised on the way IN, so the stored value is already the lookup key and a
-- lower(address) predicate would be a bug (it would build a second, redundant
-- index and hide the fact that a caller had skipped normalisation).
--
-- The CHECK enforces that. Without it, one writer that forgot to lowercase would
-- insert a row that no read will ever match - a suppression that silently does
-- nothing, which is the worst possible failure for this table because it looks
-- exactly like success.
ALTER TABLE email_suppressions
    ADD CONSTRAINT ck_email_suppressions_address_lowercased
        CHECK (address = lower(address) AND address = btrim(address) AND address <> '');

-- "What has been bouncing lately", newest first. An operational view rather than
-- a read path: nothing in the send path orders by this.
CREATE INDEX idx_email_suppressions_created_at ON email_suppressions (created_at DESC);

-- ============================================================================
-- ses_notification_events: every SNS delivery we receive, valid or not.
--
-- Modelled deliberately on payment_webhook_events (V6) rather than invented
-- fresh, because it is the same problem with the same three requirements, and
-- an operator who has debugged one should recognise the other immediately:
--
--   1. AUDIT. A public endpoint that can disable a customer's email needs to be
--      able to answer "why did this address stop receiving mail" with the exact
--      bytes AWS sent, months later.
--   2. REJECTIONS ARE THE INTERESTING ROWS. A message whose signature did not
--      verify is written here with signature_valid = FALSE and processed =
--      FALSE. Somebody probing this endpoint is otherwise completely invisible;
--      a run of such rows is the alert.
--   3. IDEMPOTENCY. SNS guarantees at-least-once delivery and retries anything
--      that is not answered 2xx, so the same notification WILL arrive twice.
--      See the partial unique index below.
--
-- Not tenant-scoped, for the same reason payment_webhook_events is not: the
-- endpoint is public, TenantContext is empty, and the Hibernate tenant filter is
-- off for the whole request. A client_id here would either have to be invented
-- or left null, and neither is a fact the notification contains.
-- ============================================================================
CREATE TABLE ses_notification_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- SNS's MessageId from the envelope. Stable across retries of the same
    -- notification, which is exactly what makes it the idempotency key.
    -- NULLABLE on purpose: a malformed or truncated body has no MessageId, and
    -- that body is the one you most want kept. Postgres treats NULLs as distinct
    -- in a unique index, so any number of unidentifiable rows coexist happily.
    message_id          VARCHAR(200),

    -- The SNS envelope's "Type": Notification, SubscriptionConfirmation,
    -- UnsubscribeConfirmation. Not the SES event type - see below.
    message_type        VARCHAR(60),

    -- The SES event nested inside a Notification: Bounce, Complaint, Delivery...
    -- Two columns rather than one because they answer different questions and
    -- both get asked: "is our topic subscription healthy" reads message_type,
    -- "what is our bounce rate" reads notification_type.
    notification_type   VARCHAR(60),

    -- Permanent / Transient / Undetermined for a bounce; the complaint feedback
    -- type (abuse, fraud, not-spam...) for a complaint. This is the field that
    -- decides whether anything was suppressed, so it is stored rather than left
    -- buried in the payload - "show me every Permanent bounce this week" must
    -- not require a JSONB scan.
    sub_type            VARCHAR(60),

    signature_valid     BOOLEAN NOT NULL,
    processed           BOOLEAN NOT NULL DEFAULT FALSE,

    -- Why it was (or was not) acted on, in one line. The first thing anyone reads
    -- when a customer says their mail stopped.
    processing_note     VARCHAR(500),

    -- The SNS envelope exactly as received. JSONB rather than TEXT so
    -- "what did AWS actually say" stays queryable; a body that is not valid JSON
    -- is wrapped by the writer rather than dropped.
    payload             JSONB NOT NULL,

    received_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- THE IDEMPOTENCY BACKSTOP, AND WHY IT IS PARTIAL
--
-- At most one PROCESSED row may exist per MessageId. That is the invariant that
-- makes a redelivered notification a no-op even if two of them race on two
-- application instances: the second one's INSERT fails and the handler treats
-- the failure as "already done".
--
-- Why not a plain UNIQUE (message_id)? Because the rows we refuse also have to
-- be stored, and there may be many of them for one id: a signature that failed
-- verification (recorded, processed = FALSE), then AWS retrying, then the same
-- notification arriving again after it was already applied. Under a plain unique
-- constraint the second of those would throw instead of being recorded, and we
-- would lose the evidence of the retry - which per point (2) above is the row
-- that matters most.
--
-- Partial on processed = TRUE gets both: unlimited audit rows, exactly one
-- effective application.
-- ============================================================================
CREATE UNIQUE INDEX uq_ses_notification_events_processed_message_id
    ON ses_notification_events (message_id)
    WHERE processed = TRUE AND message_id IS NOT NULL;

-- Duplicate detection reads this before doing any work, and it must find both
-- processed and unprocessed rows for an id, so the partial index above cannot
-- serve it.
CREATE INDEX idx_ses_notification_events_message_id ON ses_notification_events (message_id);

-- Operational: "what has SNS been sending us", newest first.
CREATE INDEX idx_ses_notification_events_received_at ON ses_notification_events (received_at DESC);
