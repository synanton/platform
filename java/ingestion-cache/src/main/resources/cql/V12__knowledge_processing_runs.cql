-- Canonical knowledge processing runs (Design 1.25 §12): permanent provenance
-- objects recording the execution that produced derived knowledge state.
CREATE TABLE IF NOT EXISTS ingestion_cache.knowledge_processing_runs (
  tenant_id           text,
  processing_run_id   uuid,
  producer            text,
  producer_version    text,
  configuration_json  text,
  input_json          text,
  output_json         text,
  status              text,
  started_at          timestamp,
  completed_at        timestamp,
  error_code          text,
  error_message       text,
  PRIMARY KEY ((tenant_id), processing_run_id)
);

-- Chunks carry the full provenance chain: the source version they derive from
-- and the processing run that produced them (§13: Source → Chunk → Processing Run).
ALTER TABLE ingestion_cache.chunks_payload ADD source_version_id uuid;
ALTER TABLE ingestion_cache.chunks_payload ADD processing_run_id uuid;

-- The content manifest links the artifact to the run that published it.
ALTER TABLE ingestion_cache.manifest ADD processing_run_id uuid;
