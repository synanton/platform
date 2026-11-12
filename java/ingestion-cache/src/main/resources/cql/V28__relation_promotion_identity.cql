-- ADR-029: promotion-row identity. Legacy rows must remain explicitly
-- unversioned; do not fabricate source/generation values for them.
ALTER TABLE ingestion_cache.relation_promotion_results ADD source_id text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD revision_id uuid;
ALTER TABLE ingestion_cache.relation_promotion_results ADD generation bigint;
ALTER TABLE ingestion_cache.relation_promotion_results ADD extraction_run_id text;
ALTER TABLE ingestion_cache.relation_promotion_results ADD identity_status text;
