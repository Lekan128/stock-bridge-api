-- Notifications on phones and computers (Web Push), STOCK_AREAS_PART_5.md (owner, 2026-11-01): when the Kitchen
-- asks, the Store's people hear about it even with the app closed; when it's moved, it quietly says so and goes.

-- Each device a person turned notifications on for. The browser's push service gives the endpoint and the keys;
-- a device that turns them off, or that the push service says is gone (404/410), is removed.
CREATE TABLE push_subscriptions (
    id           UUID PRIMARY KEY,
    client_id    UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    user_id      UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint     VARCHAR(1000) NOT NULL,
    p256dh       VARCHAR(200)  NOT NULL,
    auth         VARCHAR(100)  NOT NULL,
    user_agent   VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_sent_at TIMESTAMPTZ,
    CONSTRAINT uq_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);

-- The server's own key pair (VAPID), made on first use and kept, so devices that subscribed stay subscribed across
-- restarts and deploys. One row.
CREATE TABLE push_vapid (
    id          SMALLINT PRIMARY KEY CHECK (id = 1),
    public_key  VARCHAR(200)  NOT NULL,
    private_key VARCHAR(400)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
