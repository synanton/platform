-- RAKG PR #3.5: independent graph-commit retry state.
ALTER TABLE ingestion_cache.source_versions ADD graph_attempt_count int;
ALTER TABLE ingestion_cache.source_versions ADD graph_next_attempt_at timestamp;
