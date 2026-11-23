-- #844: durable prompt/schema provenance on extraction results.
-- Nulls mean historical/unverified; no retroactive backfill.
ALTER TABLE ingestion_cache.extraction_results ADD prompt_version text;
ALTER TABLE ingestion_cache.extraction_results ADD prompt_compiled_digest text;
ALTER TABLE ingestion_cache.extraction_results ADD prompt_rendered_digest text;
ALTER TABLE ingestion_cache.extraction_results ADD schema_version text;
ALTER TABLE ingestion_cache.extraction_results ADD schema_digest text;
