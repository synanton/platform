-- H2 schema for @JdbcTest slices - no Flyway involved.
CREATE SCHEMA IF NOT EXISTS annotations;

CREATE TABLE IF NOT EXISTS annotations.annotation_definitions (
    definition_id   VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    annotation_type VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_annotation_definitions PRIMARY KEY (definition_id),
    CONSTRAINT uq_annotation_definitions_namespace_name UNIQUE (namespace, name)
);

CREATE TABLE IF NOT EXISTS annotations.annotation_definition_versions (
    definition_id     VARCHAR(255) NOT NULL,
    version           INT          NOT NULL,
    inputs_json       CLOB         NOT NULL DEFAULT '[]',
    producer          VARCHAR(255) NOT NULL,
    producer_version  VARCHAR(64)  NOT NULL,
    output_type       VARCHAR(64)  NOT NULL,
    output_name       VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    published_at      TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_annotation_definition_versions PRIMARY KEY (definition_id, version),
    CONSTRAINT fk_versions_definition FOREIGN KEY (definition_id)
        REFERENCES annotations.annotation_definitions (definition_id)
);

CREATE TABLE IF NOT EXISTS annotations.dependency_edges (
    from_definition_id VARCHAR(255) NOT NULL,
    from_version        INT          NOT NULL,
    to_definition_id     VARCHAR(255) NOT NULL,
    to_version            INT          NOT NULL,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dependency_edges PRIMARY KEY (from_definition_id, from_version, to_definition_id, to_version)
);

CREATE TABLE IF NOT EXISTS annotations.processing_runs (
    processing_run_id         UUID         NOT NULL DEFAULT RANDOM_UUID(),
    producer                  VARCHAR(255) NOT NULL,
    producer_version          VARCHAR(64)  NOT NULL,
    tenant_id                 VARCHAR(255) NOT NULL,
    definition_id             VARCHAR(255),
    definition_version        INT,
    scope                     VARCHAR(255),
    started_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at                  TIMESTAMP,
    status                    VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    error_summary             CLOB,
    resource_consumption_json CLOB,
    CONSTRAINT pk_processing_runs PRIMARY KEY (processing_run_id)
);
