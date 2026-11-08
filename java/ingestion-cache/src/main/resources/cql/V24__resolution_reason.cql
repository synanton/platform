-- RAKG PR #4b.3: persist the policy reason code with each decision.
ALTER TABLE ingestion_cache.resolution_results ADD reason_code text;
