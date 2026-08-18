-- Tenants table (platform-style tenant registry)
CREATE TABLE IF NOT EXISTS topology.tenants (
    tenant_id    TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the demo tenant
INSERT INTO topology.tenants (tenant_id, display_name, created_at)
VALUES ('demo', 'Demo Tenant', now())
ON CONFLICT (tenant_id) DO NOTHING;

-- Outbox for ACL change events (dispatched to Kafka when available)
CREATE TABLE IF NOT EXISTS topology.topology_outbox (
    event_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    TEXT NOT NULL,
    payload       JSONB NOT NULL,
    dispatched    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_undispatched
    ON topology.topology_outbox (dispatched, created_at) WHERE dispatched = FALSE;

-- Tenant rate/cost policies
CREATE TABLE IF NOT EXISTS topology.tenant_policies (
    tenant_id         TEXT PRIMARY KEY,
    qps_limit         INT NOT NULL DEFAULT 10,
    monthly_usd_limit NUMERIC(10,2) NOT NULL DEFAULT 10.00,
    max_latency_ms    INT NOT NULL DEFAULT 5000,
    effective_from    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Connector registry for relix
CREATE TABLE IF NOT EXISTS topology.connectors (
    connector_id TEXT PRIMARY KEY,
    address      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO topology.connectors (connector_id, address) VALUES
    ('in-memory', 'localhost:9090')
ON CONFLICT (connector_id) DO NOTHING;

-- Extend acl_grants for Phase 3 text-based subject_id and revocation
ALTER TABLE topology.acl_grants
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tenant_id TEXT;

-- Seed policies for demo tenants
INSERT INTO topology.tenant_policies (tenant_id, qps_limit, monthly_usd_limit)
VALUES ('demo', 10, 10.00)
ON CONFLICT (tenant_id) DO NOTHING;
