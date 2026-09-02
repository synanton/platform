-- v1.24/1.25 AAP-1: annotation identity - definitions, versions, dependency DAG, processing runs.
-- See docs/architecture/synanton-design-1.25.md §6-§14 and
-- docs/implementation/annotations-analytics-plane/01-annotation-foundation.md

CREATE SCHEMA IF NOT EXISTS annotations;

CREATE TABLE annotations.annotation_definitions (
    definition_id   TEXT PRIMARY KEY,
    namespace       TEXT NOT NULL,
    name            TEXT NOT NULL,
    annotation_type TEXT NOT NULL
        CHECK (annotation_type IN ('TAG', 'CLASSIFICATION', 'ENTITY', 'ATTRIBUTE', 'SIGNAL')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_annotation_definitions_namespace_name UNIQUE (namespace, name)
);

-- Design §8: "Definitions are immutable once published. A new definition version
-- must be explicitly registered." status follows the §72 lifecycle
-- (Draft -> Validated -> Published -> Deprecated -> Retired); only DRAFT/VALIDATED
-- versions may still be edited.
CREATE TABLE annotations.annotation_definition_versions (
    definition_id     TEXT NOT NULL REFERENCES annotations.annotation_definitions(definition_id),
    version           INT NOT NULL,
    inputs_json       TEXT NOT NULL DEFAULT '[]',
    producer          TEXT NOT NULL,
    producer_version  TEXT NOT NULL,
    output_type       TEXT NOT NULL,
    output_name       TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'DEPRECATED', 'RETIRED')),
    published_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (definition_id, version)
);

-- Design §10-§11: dependency DAG between annotation definition versions, distinct from
-- taxonomy. Circular dependencies (A -> B -> C -> A) are rejected at the application
-- layer (DependencyGraphService) before insert - see the plan doc's Resolutor note.
CREATE TABLE annotations.dependency_edges (
    from_definition_id TEXT NOT NULL,
    from_version        INT NOT NULL,
    to_definition_id    TEXT NOT NULL,
    to_version           INT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (from_definition_id, from_version, to_definition_id, to_version),
    CONSTRAINT fk_dependency_edges_from
        FOREIGN KEY (from_definition_id, from_version)
        REFERENCES annotations.annotation_definition_versions (definition_id, version),
    CONSTRAINT fk_dependency_edges_to
        FOREIGN KEY (to_definition_id, to_version)
        REFERENCES annotations.annotation_definition_versions (definition_id, version),
    CONSTRAINT chk_dependency_edges_no_self_loop
        CHECK (NOT (from_definition_id = to_definition_id AND from_version = to_version))
);

CREATE INDEX idx_dependency_edges_to
    ON annotations.dependency_edges (to_definition_id, to_version);

-- Design §12: processing runs are permanent provenance objects subject to retention policy.
CREATE TABLE annotations.processing_runs (
    processing_run_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    producer                  TEXT NOT NULL,
    producer_version          TEXT NOT NULL,
    tenant_id                 TEXT NOT NULL,
    definition_id             TEXT,
    definition_version        INT,
    scope                     TEXT,
    started_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                  TIMESTAMPTZ,
    status                    TEXT NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_summary             TEXT,
    resource_consumption_json TEXT
);

CREATE INDEX idx_processing_runs_tenant_started
    ON annotations.processing_runs (tenant_id, started_at DESC);
