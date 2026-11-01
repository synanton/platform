-- Drop the legacy outbox table; its write path (TenantController + JdbcOutboxRepository)
-- moved to the shared event_outbox in 2b. Single-deploy: ships with the code that stopped writing it.
DROP TABLE IF EXISTS topology.topology_outbox;
