-- ADR-029 §12.4: distinct consumer liveness heartbeat for closed ownership sets.
ALTER TABLE ingestion_cache.relation_promotion_results ADD consumer_last_write_at timestamp;
