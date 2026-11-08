-- RAKG PR #3: separate graph-commit state from extraction_status.
ALTER TABLE ingestion_cache.source_versions ADD graph_status text;
ALTER TABLE ingestion_cache.source_versions ADD graph_commit_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD graph_validation_counters map<text, int>;
