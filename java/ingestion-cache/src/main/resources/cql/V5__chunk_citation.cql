ALTER TABLE ingestion_cache.chunks_payload ADD source_elements text;
ALTER TABLE ingestion_cache.chunks_payload ADD token_count int;
ALTER TABLE ingestion_cache.chunks_payload ADD structured_content text;
ALTER TABLE ingestion_cache.chunks_payload ADD is_partial_section boolean;
