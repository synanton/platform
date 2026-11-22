-- ADR-029 authoritative activation record. Cassandra owns currentness; graph/search carry applied markers.
CREATE TABLE IF NOT EXISTS ingestion_cache.source_activation (
    tenant_id text,
    source_id text,
    generation bigint,
    revision_id uuid,
    connector_id text,
    resource_id text,
    previous_revision_id uuid,
    state text,
    content_hash text,
    graph_applied_generation bigint,
    graph_applied_state text,
    search_applied_generation bigint,
    search_applied_state text,
    last_error text,
    retry_eligible boolean,
    created_at timestamp,
    updated_at timestamp,
    activated_at timestamp,
    PRIMARY KEY ((tenant_id, source_id), generation)
) WITH CLUSTERING ORDER BY (generation DESC);

CREATE TABLE IF NOT EXISTS ingestion_cache.source_current (
    tenant_id text,
    source_id text,
    current_generation bigint,
    active_revision_id uuid,
    pending_generation bigint,
    pending_revision_id uuid,
    updated_at timestamp,
    PRIMARY KEY ((tenant_id, source_id))
);
