-- #826: quarantine persistence by extraction generation.
--
-- Generations are versioned; records are never overwritten by a re-extraction.
-- The current pointer is updated by Cassandra LWT, not application-only logic.
CREATE TABLE IF NOT EXISTS ingestion_cache.quarantine_generations (
  tenant_id              text,
  source_version_id      uuid,
  extraction_generation  uuid,
  source_document_id     text,
  prompt_version         text,
  prompt_digest          text,
  schema_digest          text,
  model_id               text,
  model_revision         text,
  extraction_started_at  timestamp,
  extraction_run_id      uuid,
  generation_status      text,
  superseded_by          uuid,
  promoted_at            timestamp,
  promoted_by            text,
  promotion_reason       text,
  previous_generation    uuid,
  deletion_mode          text,
  deleted_at             timestamp,
  deleted_reason         text,
  PRIMARY KEY ((tenant_id, source_version_id), extraction_generation)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.quarantine_records (
  tenant_id              text,
  source_version_id      uuid,
  extraction_generation  uuid,
  chunk_ordinal          int,
  line_number            int,
  reason_code            text,
  category               text,
  detail                 text,
  raw_line_sha256        text,
  raw_line               text,
  captured_at            timestamp,
  disposition            text,
  disposition_at         timestamp,
  disposition_by         text,
  disposition_reason     text,
  retention_expires_at   timestamp,
  PRIMARY KEY ((tenant_id, source_version_id), extraction_generation, chunk_ordinal, line_number)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.quarantine_current_generation (
  tenant_id            text,
  source_version_id    uuid,
  current_generation   uuid,
  generation_version   bigint,
  updated_at           timestamp,
  PRIMARY KEY ((tenant_id, source_version_id))
);
