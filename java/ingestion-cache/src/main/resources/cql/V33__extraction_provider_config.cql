-- #728: per-tenant extraction provider selection and run attribution.
CREATE TABLE IF NOT EXISTS ingestion_cache.tenant_provider_config (
  tenant_id  text PRIMARY KEY,
  provider   text,
  updated_at timestamp
);

ALTER TABLE ingestion_cache.source_versions ADD extraction_provider text;
