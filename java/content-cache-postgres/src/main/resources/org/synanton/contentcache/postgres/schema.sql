CREATE TABLE IF NOT EXISTS artifacts (
    tenant_id         TEXT        NOT NULL,
    artifact_id      UUID        NOT NULL,
    digest           TEXT        NOT NULL,
    generation       INTEGER     NOT NULL DEFAULT 1,
    serializer_version INTEGER   NOT NULL DEFAULT 1,
    content_type     TEXT,
    size_bytes       BIGINT      NOT NULL DEFAULT 0,
    storage_tier     TEXT        NOT NULL DEFAULT 'inline',
    payload          BYTEA,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, artifact_id),
    CONSTRAINT uq_artifacts_tenant_digest UNIQUE (tenant_id, digest)
);

CREATE INDEX IF NOT EXISTS idx_artifacts_tenant_created
    ON artifacts (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS retention_policies (
    tenant_id      TEXT        NOT NULL,
    artifact_id    UUID        NOT NULL,
    owner_id       TEXT        NOT NULL,
    retention_days INTEGER     NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    legal_hold     BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, artifact_id, owner_id),
    FOREIGN KEY (tenant_id, artifact_id) REFERENCES artifacts (tenant_id, artifact_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_retention_expires
    ON retention_policies (expires_at)
    WHERE legal_hold = false;

CREATE TABLE IF NOT EXISTS reference_registry (
    tenant_id    TEXT        NOT NULL,
    digest      TEXT        NOT NULL,
    owner_id    TEXT        NOT NULL,
    owner_type  TEXT        NOT NULL DEFAULT 'unknown',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, digest, owner_id),
    FOREIGN KEY (tenant_id, digest) REFERENCES artifacts (tenant_id, digest) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reference_registry_tenant_digest
    ON reference_registry (tenant_id, digest);

CREATE INDEX IF NOT EXISTS idx_reference_registry_owner
    ON reference_registry (tenant_id, owner_id);

CREATE TABLE IF NOT EXISTS audit_log (
    id             BIGSERIAL   PRIMARY KEY,
    timestamp      TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor          TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    tenant_id      TEXT        NOT NULL,
    artifact_id    UUID,
    scope          TEXT,
    reason         TEXT,
    before_state   TEXT,
    after_state    TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant
    ON audit_log (tenant_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_audit_action
    ON audit_log (action, timestamp DESC);

-- Tenant isolation (platform #339): every tenant-scoped table enforces RLS.
-- current_setting(..., true) with missing_ok=true yields NULL when no scope is
-- set, so an unscoped query matches no rows (fail-closed, never all rows).
-- FORCE covers table owners as well; app-role tests exercise the policy path.
ALTER TABLE artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE artifacts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON artifacts;
CREATE POLICY tenant_isolation ON artifacts
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE retention_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_policies FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON retention_policies;
CREATE POLICY tenant_isolation ON retention_policies
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE reference_registry ENABLE ROW LEVEL SECURITY;
ALTER TABLE reference_registry FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reference_registry;
CREATE POLICY tenant_isolation ON reference_registry
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON audit_log;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = current_setting('app.tenant_id', true));
