-- Keyspace for the slice's ingestion cache, created BEFORE synflux boots.
--
-- The service's own migration (V1__baseline.cql) creates the keyspace with
-- SimpleStrategy RF=1, which is correct for the single-node compose stack but
-- provides no redundancy on a 3-node cluster. `CREATE IF NOT EXISTS` means a
-- pre-created keyspace wins — this one, with NetworkTopologyStrategy RF=3.
--
-- Apply (deploy.sh does it): cqlsh on cassandra-0.
CREATE KEYSPACE IF NOT EXISTS ingestion_cache
  WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3};
