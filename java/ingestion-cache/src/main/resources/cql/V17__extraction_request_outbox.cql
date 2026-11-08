-- RAKG Phase 2 (PR #1): extraction request event + projection readiness.
--
-- The source outbox is the source_versions row itself (one event slot today),
-- partitioned by (tenant_id, connector_id, source_id, resource_id). A second
-- event row would land on the same partition, but the existing LWT writes one
-- immutable row; adding a second event slot to that row keeps both events
-- atomic without a cross-partition conditional batch.
--
-- ExtractionRequested is stored in extraction_event_*; the extraction consumer
-- must gate on search_projected_at IS NOT NULL before calling the LLM.
ALTER TABLE ingestion_cache.source_versions ADD search_projected_at timestamp;

ALTER TABLE ingestion_cache.source_versions ADD extraction_event_id uuid;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_type text;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_payload_json text;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_published boolean;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_published_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_attempt_count int;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_last_error text;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_next_attempt_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD extraction_event_dead_lettered boolean;
