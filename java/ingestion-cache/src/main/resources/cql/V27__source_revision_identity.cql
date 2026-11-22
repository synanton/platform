-- ADR-029 source-revision identity and generation fencing.
ALTER TABLE ingestion_cache.source_versions ADD content_hash text;
ALTER TABLE ingestion_cache.source_versions ADD generation int;
ALTER TABLE ingestion_cache.source_versions ADD accepted_generation int;
ALTER TABLE ingestion_cache.source_versions ADD current_generation int;
ALTER TABLE ingestion_cache.source_versions ADD previous_revision_id uuid;
