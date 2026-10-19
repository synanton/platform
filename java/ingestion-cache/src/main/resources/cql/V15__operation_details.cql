-- Open-typed Operation details (W2: details is open-typed — no contract
-- revision). The anti-omission projection (#294) writes
-- {"feature_states": {...}, "payload_descriptor": {...}} here; a requested
-- feature reported FEATURE_UNSUPPORTED makes the outcome PARTIAL.
ALTER TABLE ingestion_cache.operations ADD details_json text;
