CREATE SCHEMA IF NOT EXISTS syntology;

CREATE TABLE syntology.ontology_versions (
    version_id   UUID         NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'demo',
    version      VARCHAR(32)  NOT NULL UNIQUE,
    label        VARCHAR(255),
    description  TEXT,
    graph_uri    VARCHAR(512),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ontology_versions PRIMARY KEY (version_id)
);
