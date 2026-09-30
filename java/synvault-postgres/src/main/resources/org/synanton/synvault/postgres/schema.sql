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

-- PG-POC-007-7a: generation pointer (YDB P0-1 equivalent shape). YDB holds
-- active generations in an engine-memory map (first-write-wins adopt,
-- rebuild flips via put); PG holds one row per tenant — a single-row
-- upsert is the atomic flip, and DB state (not engine memory) is what
-- survives restarts and shares across instances. Same contract either way:
-- a query never observes two generations. RLS-isolated like the rest.
CREATE TABLE IF NOT EXISTS quest_generations (
    tenant_id         text PRIMARY KEY,
    active_generation text NOT NULL
);

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

ALTER TABLE quest_generations ENABLE ROW LEVEL SECURITY;
ALTER TABLE quest_generations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quest_generations
    USING (tenant_id = current_setting('app.tenant_id', true));

-- PG-POC-007-7a: controlled RLS bypasses for port-level key resolution.
-- The quest port gives delete()/rebuild() no tenant scope (YDB scans its
-- local per-tenant indexes instead); these functions resolve keys the app
-- role cannot see. Narrow by shape, SECURITY DEFINER so they run as the
-- installer. Ownership requirement: must be owned by a BYPASSRLS-capable
-- role (tests run as the container superuser, so automatic; production
-- migration must enforce it — PoC follow-up, not PoC scope). FORCE RLS
-- still binds the tables themselves.
-- Splitter constraint (PostgresSchema: one statement per ';' at line end,
-- no dollar-quoting): single-statement SQL bodies only — the flip/reset
-- pair below replaces one conditional function. Full reset clears
-- quest-written rows only: the store path never sets generation_id
-- (nullable by design), so IS NOT NULL selects exactly the quest
-- projection surface (YDB's per-tenant-index delete equivalent).
CREATE OR REPLACE FUNCTION quest_chunk_tenants(p_chunk_id text, p_generation_id text)
RETURNS TABLE (tenant_id text)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$ SELECT tenant_id FROM chunks
   WHERE chunk_id = p_chunk_id AND generation_id = p_generation_id $$;

CREATE OR REPLACE FUNCTION quest_promote_flip(p_generation text)
RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public AS
'UPDATE quest_generations SET active_generation = p_generation';
CREATE OR REPLACE FUNCTION quest_reset_quest_rows()
RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public AS
'DELETE FROM chunks WHERE generation_id IS NOT NULL';
