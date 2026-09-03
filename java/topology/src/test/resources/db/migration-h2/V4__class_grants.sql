CREATE TABLE IF NOT EXISTS topology.class_grants (
    grant_id           UUID NOT NULL DEFAULT RANDOM_UUID(),
    org_id             UUID NOT NULL,
    subject_id         UUID NOT NULL,
    subject_type       VARCHAR(10) NOT NULL,
    class              VARCHAR(16) NOT NULL,
    permission         VARCHAR(10) NOT NULL,
    tenant_id          VARCHAR(255),
    subject_key        VARCHAR(128) NOT NULL,
    propagation_state  VARCHAR(32) NOT NULL DEFAULT 'PENDING_PROPAGATION',
    propagated_at      TIMESTAMP,
    revoked_at         TIMESTAMP,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_class_grants PRIMARY KEY (grant_id),
    CONSTRAINT fk_class_grants_org FOREIGN KEY (org_id) REFERENCES topology.organizations(org_id)
);

CREATE INDEX IF NOT EXISTS idx_class_grants_subject
    ON topology.class_grants (tenant_id, subject_key, class);
