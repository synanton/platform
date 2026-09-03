-- v1.23 SEC-1: sensitivity-class grants (orthogonal to resource ACLs).

CREATE TABLE IF NOT EXISTS topology.class_grants (
    grant_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID NOT NULL REFERENCES topology.organizations(org_id),
    subject_id         UUID NOT NULL,
    subject_type       TEXT NOT NULL CHECK (subject_type IN ('USER', 'GROUP', 'ROLE')),
    class              TEXT NOT NULL CHECK (class IN ('PUBLIC', 'PERSONAL', 'FINANCIAL', 'RESTRICTED')),
    permission         TEXT NOT NULL CHECK (permission IN ('SEARCH', 'VIEW')),
    tenant_id          TEXT,
    subject_key        TEXT NOT NULL,
    propagation_state  TEXT NOT NULL DEFAULT 'PENDING_PROPAGATION'
                           CHECK (propagation_state IN ('PENDING_PROPAGATION', 'PROPAGATED', 'STUCK')),
    propagated_at      TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_class_grants_subject
    ON topology.class_grants (tenant_id, subject_key, class)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_class_grants_stuck
    ON topology.class_grants (propagation_state)
    WHERE propagation_state = 'STUCK';
