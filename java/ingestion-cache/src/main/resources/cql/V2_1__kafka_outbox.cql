CREATE TABLE IF NOT EXISTS ingestion_cache.manifest_transitions_outbox (
  tenant_id       text,
  event_id        timeuuid,
  manifest_id     uuid,
  transition_from text,
  transition_to   text,
  topic           text,
  payload_json    text,
  published       boolean,
  created_at      timestamp,
  PRIMARY KEY ((tenant_id), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC);
