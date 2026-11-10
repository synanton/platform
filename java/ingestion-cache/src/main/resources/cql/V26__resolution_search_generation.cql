-- RAKG #577: tie a resolution decision to the search-projection generation used
-- for candidate retrieval.
ALTER TABLE ingestion_cache.resolution_results ADD search_generation text;
