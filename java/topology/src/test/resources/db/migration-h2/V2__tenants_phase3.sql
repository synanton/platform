-- H2-compatible Phase 3 migration.
-- Mirrors production V2__tenants_phase3.sql but uses H2 syntax.

CREATE TABLE IF NOT EXISTS topology.tenants (
    tenant_id    VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

MERGE INTO topology.tenants (tenant_id, display_name, created_at)
    KEY(tenant_id)
    VALUES ('demo', 'Demo Tenant', CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS topology.topology_outbox (
    event_id      UUID NOT NULL DEFAULT RANDOM_UUID(),
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    dispatched    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMP,
    CONSTRAINT pk_topology_outbox PRIMARY KEY (event_id)
);
CREATE INDEX IF NOT EXISTS idx_outbox_undispatched
    ON topology.topology_outbox (dispatched, created_at);

CREATE TABLE IF NOT EXISTS topology.tenant_policies (
    tenant_id         VARCHAR(255) PRIMARY KEY,
    qps_limit         INT NOT NULL DEFAULT 10,
    monthly_usd_limit NUMERIC(10,2) NOT NULL DEFAULT 10.00,
    max_latency_ms    INT NOT NULL DEFAULT 5000,
    effective_from    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS topology.connectors (
    connector_id VARCHAR(255) PRIMARY KEY,
    address      VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
MERGE INTO topology.connectors (connector_id, address)
    KEY(connector_id)
    VALUES ('in-memory', 'localhost:9090');

ALTER TABLE topology.acl_grants
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;
ALTER TABLE topology.acl_grants
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);

MERGE INTO topology.tenant_policies (tenant_id, qps_limit, monthly_usd_limit)
    KEY(tenant_id)
    VALUES ('demo', 10, 10.00);
