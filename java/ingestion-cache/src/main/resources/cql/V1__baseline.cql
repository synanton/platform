CREATE KEYSPACE IF NOT EXISTS ingestion_cache
  WITH replication = {'class':'SimpleStrategy','replication_factor':1};

CREATE TABLE IF NOT EXISTS ingestion_cache.manifest (
  tenant_id            text,
  content_ref_id       uuid,
  ingested_at          timestamp,
  schema_version       int,
  chunk_strategy       text,
  chunk_strategy_version int,
  state                text,
  storage_tier         text,
  archive_location     text,
  source_uri           text,
  source_sha256        text,
  size_bytes           bigint,
  mime_type            text,
  embedding_quality    text,
  enrichment_model_id  text,
  PRIMARY KEY ((tenant_id, content_ref_id))
);

CREATE TABLE IF NOT EXISTS ingestion_cache.chunks_payload (
  tenant_id            text,
  content_ref_id       uuid,
  chunk_ordinal        int,
  chunk_text           text,
  chunk_sha256         text,
  PRIMARY KEY ((tenant_id, content_ref_id), chunk_ordinal)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.jobs (
  tenant_id            text,
  job_id               uuid,
  started_at           timestamp,
  completed_at         timestamp,
  state                text,
  source               text,
  source_path          text,
  processed_count      int,
  error_count          int,
  last_error           text,
  enriched_count       int,
  embedded_count       int,
  enrichment_cache_hits int,
  embedding_cache_hits  int,
  enrichment_errors     int,
  embedding_errors      int,
  skipped_already_embedded int,
  PRIMARY KEY ((tenant_id), started_at, job_id)
) WITH CLUSTERING ORDER BY (started_at DESC, job_id ASC);

CREATE TABLE IF NOT EXISTS ingestion_cache.analysis_cache (
  tenant_id            text,
  content_ref_id       uuid,
  chunk_ordinal        int,
  pass_number          int,
  model_id             text,
  prompt_version       text,
  analysis_json        text,
  input_sha256         text,
  created_at           timestamp,
  PRIMARY KEY ((tenant_id, content_ref_id), chunk_ordinal, pass_number)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.embedding_content_cache (
  tenant_id            text,
  content_ref_id       uuid,
  chunk_ordinal        int,
  model_id             text,
  chunk_sha256         text,
  embedding            blob,
  embedding_dim        int,
  created_at           timestamp,
  PRIMARY KEY ((tenant_id, content_ref_id), chunk_ordinal, model_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.image_caption_cache (
  tenant_id            text,
  content_ref_id       uuid,
  image_ordinal        int,
  model_id             text,
  caption_text         text,
  input_sha256         text,
  created_at           timestamp,
  PRIMARY KEY ((tenant_id, content_ref_id), image_ordinal)
);
