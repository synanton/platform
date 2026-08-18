-- Phase 4: residency / budget / rerank policy, ACL propagation, audit.

ALTER TABLE topology.organizations
    ADD COLUMN IF NOT EXISTS tenant_id TEXT UNIQUE,
    ADD COLUMN IF NOT EXISTS tier TEXT NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN IF NOT EXISTS data_residency_policy JSONB NOT NULL DEFAULT '{"allowed_regions":["us-east-1"]}',
    ADD COLUMN IF NOT EXISTS tiering_policy JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS rerank_policy JSONB NOT NULL DEFAULT '{"mode":"ALWAYS","model_family":"bge-reranker-large","candidate_pool_size":100,"top_n":20}',
    ADD COLUMN IF NOT EXISTS budget_policy JSONB NOT NULL DEFAULT '{"monthly_usd_cap":1000,"weight":100,"max_concurrent_ingest_jobs":8,"burst_credit_seconds":300}',
    ADD COLUMN IF NOT EXISTS cross_region_penalty_ms JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS outbound_auth_profiles JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS regulatory_profile TEXT,
    ADD COLUMN IF NOT EXISTS cost_privacy JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS max_context_tokens INT NOT NULL DEFAULT 32000,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE topology.organizations
SET tenant_id = 'demo'
WHERE org_id = '00000000-0000-0000-0000-000000000001' AND tenant_id IS NULL;

ALTER TABLE topology.acl_grants
    ADD COLUMN IF NOT EXISTS propagation_state TEXT NOT NULL DEFAULT 'PROPAGATED',
    ADD COLUMN IF NOT EXISTS propagated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resource_id TEXT,
    ADD COLUMN IF NOT EXISTS resource_type TEXT,
    ADD COLUMN IF NOT EXISTS subject_key TEXT;

ALTER TABLE topology.topology_outbox
    ADD COLUMN IF NOT EXISTS ack_state JSONB NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_acl_grants_stuck
    ON topology.acl_grants (propagation_state) WHERE propagation_state = 'STUCK';

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS audit.admin_audit (
    audit_id           BIGSERIAL PRIMARY KEY,
    event_time         TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_subject_id   TEXT NOT NULL,
    actor_type         TEXT NOT NULL,
    actor_role         TEXT,
    target_tenant_id   TEXT NOT NULL,
    action             TEXT NOT NULL,
    target_resource_id TEXT,
    before_state_hash  TEXT,
    after_state_hash   TEXT,
    payload            JSONB NOT NULL,
    trace_id           TEXT,
    on_behalf_of       TEXT
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_tenant_time
    ON audit.admin_audit (target_tenant_id, event_time DESC);
