-- Persist the opaque Content Cache handle separately from the legacy UUID/storage fields.
ALTER TABLE ingestion_cache.manifest ADD content_cache_reference text;
ALTER TABLE ingestion_cache.source_versions ADD content_cache_reference text;
