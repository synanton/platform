-- Gateway synthesis provenance for extraction results and reuse cache.
-- Nulls mean historical or non-Gateway paths; never fabricate identifiers.
ALTER TABLE ingestion_cache.extraction_results ADD finish_reason text;
ALTER TABLE ingestion_cache.extraction_results ADD gateway_request_id text;
ALTER TABLE ingestion_cache.extraction_results ADD gateway_execution_id text;

ALTER TABLE ingestion_cache.extraction_cache_entries ADD finish_reason text;
ALTER TABLE ingestion_cache.extraction_cache_entries ADD gateway_request_id text;
ALTER TABLE ingestion_cache.extraction_cache_entries ADD gateway_execution_id text;
