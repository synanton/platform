-- H2-compatible session pins table (test only)
-- Uses H2 UUID() and TIMESTAMP WITH TIME ZONE instead of Postgres-specific syntax
CREATE TABLE IF NOT EXISTS syntology.session_pins (
    pin_id     UUID         NOT NULL DEFAULT RANDOM_UUID(),
    tenant_id  TEXT         NOT NULL,
    version    TEXT         NOT NULL,
    subject_id TEXT         NOT NULL,
    pinned_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_session_pins PRIMARY KEY (pin_id),
    CONSTRAINT uq_session_pins_tenant_subject UNIQUE (tenant_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_session_pins_active
    ON syntology.session_pins (tenant_id, subject_id);
