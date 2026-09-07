-- Per-tenant automatic SKU generation.
--
-- One row per tenant, created lazily the first time a tenant configures this
-- feature (see ProductSkuSettingsService.update) - not seeded by this
-- migration for every existing client. Unlike marketplace_settings and
-- vendor_settlement_settings (singleton, platform-wide, always present), the
-- absence of a row here has a defined meaning: "auto-generation is off",
-- which is also the correct default for every tenant that never touches this
-- feature. Seeding a disabled row per client would say the same thing with
-- extra steps and a backfill migration this feature does not need.
--
-- pattern is the single source of truth for both the guided "Simple" builder
-- and the raw "Advanced" editor in the UI - there is no separate mode column,
-- so the two can never drift apart. Grammar (tokens like {SEQ:4}, {YYYY},
-- {NAME:3}) is validated in ProductSkuSettingsService, not by a CHECK: which
-- tokens are well-formed, and what the worst-case rendered length is, is not
-- something SQL can decide.
--
-- next_sequence/current_period_key are the counter. Both are only ever
-- touched by SkuGenerationService under a row lock (SELECT ... FOR UPDATE),
-- never by ProductSkuSettingsService - editing the pattern or flipping
-- enabled on/off must never reset or rewind the counter, so a rename doesn't
-- reissue a SKU that's already on a product.
CREATE TABLE product_sku_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id           UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,

    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    pattern             VARCHAR(100) NOT NULL DEFAULT '',

    -- NEVER / YEARLY / MONTHLY. Independent of whether pattern actually has a
    -- date token - the Advanced tab always shows this control, and it is
    -- simply inert (never triggers a rollover) when no {YYYY}/{YY}/{MM}/{DD}
    -- token is present to make a "period" meaningful.
    reset_cadence       VARCHAR(10) NOT NULL DEFAULT 'NEVER'
                        CHECK (reset_cadence IN ('NEVER', 'YEARLY', 'MONTHLY')),

    -- The next value {SEQ:N} will render. Reserved by locking this row and
    -- incrementing in Java (ProductSkuSettingsRepository.findByClientIdForUpdate),
    -- not a bare UPDATE ... RETURNING, because a reservation must also decide
    -- "has the cadence period rolled over" in the same locked step.
    next_sequence       BIGINT NOT NULL DEFAULT 1,

    -- The period next_sequence is currently counting within: '2026' under
    -- YEARLY, '2026-09' under MONTHLY, NULL under NEVER. When a reservation's
    -- current period no longer matches this column, next_sequence resets to
    -- 1 and this column is updated, inside that same locked reservation.
    current_period_key  VARCHAR(10),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_product_sku_settings_client_id UNIQUE (client_id)
);

CREATE INDEX idx_product_sku_settings_client_id ON product_sku_settings(client_id);

CREATE TRIGGER trg_product_sku_settings_set_updated_at
    BEFORE UPDATE ON product_sku_settings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN product_sku_settings.pattern IS
    'Token grammar: literal text plus {SEQ:N} (required - the only token that '
    'guarantees uniqueness), {YYYY}/{YY}/{MM}/{DD}, {NAME:N}. Validated by '
    'SkuPatternValidator at save time, including that the worst-case rendered '
    'length never exceeds products.sku''s VARCHAR(100).';

COMMENT ON COLUMN product_sku_settings.next_sequence IS
    'Reserved under a row lock by SkuGenerationService; never written by the '
    'settings-update path. A pattern edit or an enabled toggle must not reset '
    'or rewind this - see the table comment.';
