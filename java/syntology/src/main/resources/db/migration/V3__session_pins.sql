CREATE TABLE IF NOT EXISTS syntology.session_pins (
    pin_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  TEXT NOT NULL,
    version    TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    pinned_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_session_pins_active
    ON syntology.session_pins (tenant_id, subject_id) WHERE expires_at > now();
