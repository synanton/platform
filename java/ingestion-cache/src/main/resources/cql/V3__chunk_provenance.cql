ALTER TABLE ingestion_cache.chunks_payload ADD page_start int;
ALTER TABLE ingestion_cache.chunks_payload ADD page_end int;
ALTER TABLE ingestion_cache.chunks_payload ADD section_path text;
ALTER TABLE ingestion_cache.chunks_payload ADD chunk_type text;
ALTER TABLE ingestion_cache.chunks_payload ADD heading text;
