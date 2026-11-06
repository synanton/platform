-- EWP-2 Migration 3: typed-envelope metadata on the outbox tables.
-- ALTER (not CREATE) so existing clusters gain the columns; fresh installs run
-- V1..V16 in order and get them too. Cassandra has no column defaults, so
-- historical rows read these as null and the code supplies the defaults
-- (schema_version=1; replay_class from the event type declaration).
ALTER TABLE ingestion_cache.source_versions ADD schema_version int;
ALTER TABLE ingestion_cache.source_versions ADD correlation_id uuid;
ALTER TABLE ingestion_cache.source_versions ADD causation_id uuid;
ALTER TABLE ingestion_cache.source_versions ADD replay_class text;
ALTER TABLE ingestion_cache.source_versions ADD security_metadata text;

ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD schema_version int;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD correlation_id uuid;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD causation_id uuid;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD replay_class text;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD security_metadata text;
