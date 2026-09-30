-- PG-POC-003 canonical DDL (DDL-in-one-place: this file is the single
-- definition; tests and adapters call PostgresSchema, never duplicate it).
-- Proposal §8.1 schema + §8.2 index strategy.
-- Pins: Postgres 16.15, pgvector v0.8.6 (see pg-poc/001-version-manifest.md).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    tenant_id        text NOT NULL,
    doc_id           text NOT NULL,
    source_uri       text,
    title            text,
    metadata         jsonb,
    storage_revision bigint NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, doc_id)
);

CREATE TABLE chunks (
    tenant_id   text NOT NULL,
    chunk_id    text NOT NULL,
    doc_id      text NOT NULL,
    ordinal     int NOT NULL,
    text        text NOT NULL,
    token_count int NOT NULL,
    metadata    jsonb,
    embedding   vector(384),
    tsv         tsvector,
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE provenance (
    tenant_id         text NOT NULL,
    chunk_id          text NOT NULL,
    extractor         text,
    source_version_id text,
    embedding_model_ref jsonb,
    page              int,
    start_offset      int,
    end_offset        int,
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE publication_log (
    tenant_id    text NOT NULL,
    revision_id  text NOT NULL,
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (tenant_id, revision_id)
);

-- §8.2 index strategy.
CREATE INDEX chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_l2_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX chunks_tsv_gin ON chunks USING gin (tsv);
CREATE INDEX chunks_tenant_doc_btree ON chunks (tenant_id, doc_id);
CREATE INDEX chunks_metadata_gin ON chunks USING gin (metadata);
CREATE INDEX documents_metadata_gin ON documents USING gin (metadata);

-- PG-POC-007-2 (additive): quest-side write state. The synvault store path
-- (PG-POC-004) never sets these — nullable by design, not oversight.
-- Generation/promotion semantics land in 007-5/007-7; 007-2 stores the
-- values, search does not filter on them yet.
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS generation_id text;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS ordering_key bigint;

-- RLS: tenant isolation on all four tables (proposal §7.4, §8.1).
-- current_setting(..., true) with missing_ok=true: an unset app.tenant_id
-- yields NULL, which matches no rows — fail-closed, never fail-open.
-- FORCE ROW LEVEL SECURITY: PoC/test roles own the tables, and owners bypass
-- RLS by default. FORCE closes that hole so every test exercises the policy
-- path production roles will hit. Production least-privilege roles get the
-- same enforcement without FORCE.
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON documents
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chunks
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE provenance ENABLE ROW LEVEL SECURITY;
ALTER TABLE provenance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provenance
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE publication_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE publication_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON publication_log
    USING (tenant_id = current_setting('app.tenant_id', true));
