CREATE TABLE IF NOT EXISTS security.issuance_audit (
    id                   BIGSERIAL PRIMARY KEY,
    principal_id         TEXT NOT NULL,
    tenant_id            TEXT NOT NULL,
    jti                  TEXT NOT NULL UNIQUE,
    issuer               TEXT NOT NULL,
    issued_at            TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    claimed_capabilities TEXT NOT NULL,
    outcome              TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_issuance_audit_tenant
    ON security.issuance_audit (tenant_id, created_at DESC);
