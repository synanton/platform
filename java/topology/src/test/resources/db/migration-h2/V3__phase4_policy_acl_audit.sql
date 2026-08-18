-- H2-compatible Phase 4 migration.

ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS tier VARCHAR(32) DEFAULT 'STANDARD';
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS data_residency_policy TEXT DEFAULT '{"allowed_regions":["us-east-1"]}';
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS rerank_policy TEXT DEFAULT '{"mode":"ALWAYS"}';
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS budget_policy TEXT DEFAULT '{"monthly_usd_cap":1000,"weight":100}';
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS cross_region_penalty_ms TEXT DEFAULT '{}';
ALTER TABLE topology.organizations ADD COLUMN IF NOT EXISTS max_context_tokens INT DEFAULT 32000;

ALTER TABLE topology.acl_grants ADD COLUMN IF NOT EXISTS propagation_state VARCHAR(32) DEFAULT 'PROPAGATED';
ALTER TABLE topology.acl_grants ADD COLUMN IF NOT EXISTS propagated_at TIMESTAMP;
ALTER TABLE topology.acl_grants ADD COLUMN IF NOT EXISTS resource_id VARCHAR(64);
ALTER TABLE topology.acl_grants ADD COLUMN IF NOT EXISTS resource_type VARCHAR(32);
ALTER TABLE topology.acl_grants ADD COLUMN IF NOT EXISTS subject_key VARCHAR(128);

ALTER TABLE topology.topology_outbox ADD COLUMN IF NOT EXISTS ack_state TEXT DEFAULT '{}';

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS audit.admin_audit (
    audit_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_subject_id   VARCHAR(255) NOT NULL,
    actor_type         VARCHAR(64) NOT NULL,
    actor_role         VARCHAR(64),
    target_tenant_id   VARCHAR(64) NOT NULL,
    action             VARCHAR(64) NOT NULL,
    target_resource_id VARCHAR(128),
    before_state_hash  VARCHAR(64),
    after_state_hash   VARCHAR(64),
    payload            TEXT NOT NULL,
    trace_id           VARCHAR(64),
    on_behalf_of       VARCHAR(128)
);
