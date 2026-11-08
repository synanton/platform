-- RAKG PR #4a: version resolution lifecycle + durable mention decisions.
ALTER TABLE ingestion_cache.source_versions ADD resolution_status text;
ALTER TABLE ingestion_cache.source_versions ADD resolution_started_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD resolution_completed_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD resolution_attempt_count int;
ALTER TABLE ingestion_cache.source_versions ADD resolution_next_attempt_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD resolution_counters map<text, int>;

CREATE TABLE IF NOT EXISTS ingestion_cache.resolution_results (
  tenant_id               text,
  source_version_id       uuid,
  mention_id              text,
  content_ref_id          uuid,
  chunk_ordinal           int,
  chunk_offset_start      int,
  chunk_offset_end        int,
  document_offset_start   int,
  document_offset_end     int,
  surface_form            text,
  proposed_type           text,
  outcome                 text,
  resolved_entity_id      text,
  canonical_label         text,
  best_score              double,
  runner_up_score         double,
  strategy_version        text,
  ontology_version        text,
  diagnostics_json        text,
  decided_at              timestamp,
  superseded_at           timestamp,
  PRIMARY KEY ((tenant_id, source_version_id), mention_id)
);
