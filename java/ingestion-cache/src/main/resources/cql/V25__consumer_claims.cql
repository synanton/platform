-- RAKG PR #4d.1: recoverable claim lifecycle for resolution and promotion.
-- A worker owns a row through owner_id + lease_expires_at; attempt_count is the
-- fencing token for resolution graph writes. Cassandra cannot LWT on a range,
-- so expiry is read-scanned and reclaimed with a conditional owner update.
ALTER TABLE ingestion_cache.resolution_results ADD claim_status text;
ALTER TABLE ingestion_cache.resolution_results ADD claim_owner_id text;
ALTER TABLE ingestion_cache.resolution_results ADD lease_expires_at timestamp;
ALTER TABLE ingestion_cache.resolution_results ADD attempt_count int;
ALTER TABLE ingestion_cache.resolution_results ADD next_attempt_at timestamp;
ALTER TABLE ingestion_cache.resolution_results ADD claimed_at timestamp;
ALTER TABLE ingestion_cache.resolution_results ADD work_created_at timestamp;
ALTER TABLE ingestion_cache.resolution_results ADD last_error text;
ALTER TABLE ingestion_cache.resolution_results ADD failure_class text;
ALTER TABLE ingestion_cache.resolution_results ADD connector_id text;
ALTER TABLE ingestion_cache.resolution_results ADD source_id text;
ALTER TABLE ingestion_cache.resolution_results ADD resource_id text;

ALTER TABLE ingestion_cache.relation_promotion_results ADD claim_status text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD claim_owner_id text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD lease_expires_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD attempt_count int;
ALTER TABLE ingestion_cache.relation_promotion_results ADD next_attempt_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD claimed_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD last_error text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD failure_class text;
