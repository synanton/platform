-- ADR-029 §12.2 durable activation event and generation allocation.
ALTER TABLE ingestion_cache.source_versions ADD activation_event_id uuid;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_type text;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_payload_json text;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_published boolean;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_published_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_attempt_count int;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_last_error text;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_next_attempt_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD activation_event_dead_lettered boolean;

ALTER TABLE ingestion_cache.source_current ADD accepted_generation bigint;

-- Idempotent generation mapping: one generation per accepted revision.
CREATE TABLE IF NOT EXISTS ingestion_cache.source_revision_generation (
    tenant_id text,
    source_id text,
    revision_id uuid,
    generation bigint,
    connector_id text,
    resource_id text,
    created_at timestamp,
    PRIMARY KEY ((tenant_id, source_id), revision_id)
);
