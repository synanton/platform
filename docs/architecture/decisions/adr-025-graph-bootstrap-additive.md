# ADR-025: Canonical graph bootstrap is additive, never destructive

**Status:** Accepted — 2026-11-11
**Basis:** A Relix restart deleted all canonical `:Entity`/`:REL` data because the Neo4j tenant loader treated the Cassandra projection as authority and ran `DETACH DELETE`; live K8s fault injection exposed it (#589, #610).

## Decision

1. A projection/Cassandra-to-Neo4j tenant load is **additive and upsert-only**. It must not delete canonical graph data.
2. Service restart is not a deletion signal. Restarting Relix must leave existing canonical entities and relations untouched.
3. Explicit removal requires a tombstone, supersession, or retraction workflow with its own durable intent and audit trail; it is not a bootstrap/rebuild side effect.
4. A tenant load writes its node and edge upserts in one Neo4j transaction so an interruption cannot commit a partial structural replacement.
5. A partial upstream snapshot may add/update the entities it contains; it must not remove entities that are absent from that snapshot.
6. Regression coverage must seed canonical data, then exercise repeated loads, a partial snapshot, and an interrupted load. Each case asserts the existing graph survives.

Consequences:
- **Safety:** a Relix pod restart no longer wipes the tenant graph.
- **Staleness:** absent-from-snapshot nodes are not garbage-collected by a load; explicit deletion workflows own that responsibility.
- **Rollback:** a failed load transaction leaves the pre-load graph state intact.
- **Rejected:** destructive reload followed by a Cassandra snapshot. The projection is not authoritative for canonical graph deletion.

## Context

The Compose smoke and the first live K8s E2E did not restart Relix after promotion. The first live restart deleted the graph and made `/v1/entities/{id}/neighbors` return no neighbors. The fix skips `loadTenant()` for the Neo4j connector at startup, and this ADR makes the loader itself additive so future callers (including rebuild paths) cannot reintroduce the failure.
