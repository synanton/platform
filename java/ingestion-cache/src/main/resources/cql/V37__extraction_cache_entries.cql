-- #829: durable cache identity -> original generation/candidates.
-- Cache rows are deletable without deleting the generation or provenance.
CREATE TABLE IF NOT EXISTS ingestion_cache.extraction_cache_entries (
  tenant_id              text,
  source_version_id      uuid,
  cache_key              text,
  generation_id          uuid,
  entities_json          text,
  relations_json         text,
  prompt_version         text,
  prompt_compiled_digest text,
  prompt_rendered_digest text,
  schema_version         text,
  schema_digest          text,
  created_at             timestamp,
  PRIMARY KEY ((tenant_id, source_version_id), cache_key)
);
