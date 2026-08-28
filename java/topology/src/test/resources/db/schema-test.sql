-- H2 schema for @JdbcTest slice - no Flyway involved.
CREATE SCHEMA IF NOT EXISTS topology;

CREATE TABLE IF NOT EXISTS topology.organizations (
    org_id     UUID        NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id  VARCHAR(255),
    tier       VARCHAR(32) DEFAULT 'STANDARD',
    data_residency_policy TEXT,
    rerank_policy TEXT,
    budget_policy TEXT,
    cross_region_penalty_ms TEXT,
    max_context_tokens INT DEFAULT 32000,
    CONSTRAINT pk_orgs PRIMARY KEY (org_id)
);

CREATE TABLE IF NOT EXISTS topology.users (
    user_id    UUID        NOT NULL DEFAULT RANDOM_UUID(),
    org_id     UUID        NOT NULL,
    username   VARCHAR(255) NOT NULL,
    uid        INT         NOT NULL,
    gids       INT ARRAY   NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users   PRIMARY KEY (user_id),
    CONSTRAINT uq_username UNIQUE (username),
    CONSTRAINT fk_users_org FOREIGN KEY (org_id) REFERENCES topology.organizations(org_id)
);

CREATE TABLE IF NOT EXISTS topology.acl_grants (
    grant_id      UUID         NOT NULL DEFAULT RANDOM_UUID(),
    org_id        UUID         NOT NULL,
    subject_id    UUID         NOT NULL,
    subject_type  VARCHAR(10)  NOT NULL,
    resource_path VARCHAR(512) NOT NULL,
    permission    VARCHAR(10)  NOT NULL,
    source        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at    TIMESTAMP,
    tenant_id     VARCHAR(255),
    propagation_state VARCHAR(32) DEFAULT 'PROPAGATED',
    subject_key   VARCHAR(128),
    resource_id   VARCHAR(64),
    CONSTRAINT pk_grants PRIMARY KEY (grant_id),
    CONSTRAINT fk_grants_org FOREIGN KEY (org_id) REFERENCES topology.organizations(org_id)
);

CREATE TABLE IF NOT EXISTS topology.tenants (
    tenant_id    VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS topology.topology_outbox (
    event_id      UUID NOT NULL DEFAULT RANDOM_UUID(),
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    dispatched    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMP,
    ack_state     TEXT,
    CONSTRAINT pk_topology_outbox PRIMARY KEY (event_id)
);

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

-- Seed demo org
MERGE INTO topology.organizations (org_id, name)
    KEY(org_id)
    VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Org');
