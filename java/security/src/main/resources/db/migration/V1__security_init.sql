CREATE SCHEMA IF NOT EXISTS security;

CREATE TABLE security.api_keys (
    key_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       TEXT NOT NULL,
    subject_id      TEXT NOT NULL,
    key_hash        TEXT NOT NULL,
    key_lookup_hash TEXT NOT NULL,
    label           TEXT,
    scopes          TEXT[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_lookup ON security.api_keys (key_lookup_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_keys_tenant ON security.api_keys (tenant_id);

CREATE TABLE security.service_accounts (
    service_name    TEXT PRIMARY KEY,
    allowed_tenants TEXT[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO security.service_accounts (service_name, allowed_tenants) VALUES
    ('synflux-router', '{}'),
    ('synflux', '{}'),
    ('relix', '{}'),
    ('gateway', '{}'),
    ('control-plane', '{}');
