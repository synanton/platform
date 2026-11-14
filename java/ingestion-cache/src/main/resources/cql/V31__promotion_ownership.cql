-- ADR-029 §12.4: same-partition revision ownership sentinel and fencing token.
-- The sentinel row uses relation_id='__OWNERSHIP__' in the existing
-- relation_promotion_results partition (tenant_id, source_version_id).
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_producer_id text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_generation bigint;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_closed boolean;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_started_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_closed_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_takeover_count int;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_last_write_at timestamp;
ALTER TABLE ingestion_cache.relation_promotion_results ADD owner_seqno_at_insert timestamp;
