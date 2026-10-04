-- Idempotency keys for the three stock writes (stock-in, stock-out, adjustment).
--
-- A phone on a bad connection sends a stock-in, the server records it, and the response is lost on
-- the way back. The user sees "Network error", taps Confirm again, and the delivery is recorded
-- twice. With a key, the second request finds the first one's row here and is answered with the
-- stored response instead of writing a second ledger row.
--
-- The row is inserted in the SAME transaction as the stock movement it guards, so the two commit or
-- roll back together: a request that failed (say, a 409 for insufficient stock) leaves no key behind
-- and can be retried with the same key once the shelf has stock again.
CREATE TABLE stock_idempotency_keys (
    client_id       UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    -- What the key was first used for, and a hash of the exact request. The same key arriving
    -- with a different body is a client bug, answered 422 rather than silently replaying the
    -- first response for a request that asked for something else.
    operation       VARCHAR(32)  NOT NULL,
    product_id      UUID         NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, idempotency_key)
);

-- The daily sweep's only query: everything older than the retention window.
CREATE INDEX idx_stock_idempotency_keys_created_at ON stock_idempotency_keys (created_at);
