# Graph identity authority contract

**Status:** Proposed for review — 2026-11-11
**Depends on:** [ADR-026](../architecture/decisions/adr-026-canonical-graph-projection-scope.md)
**Scope:** Gates A–C for #618. No graph code changes until this contract is accepted.

## 1. Ownership

| Concept | Owner | Authority |
|---|---|---|
| Active ontology version per tenant | Syntology | Authoritative for "what ontology is currently active" |
| Projection version the canonical graph was built against | Relix `:CanonicalGraphMetadata` | Authoritative for "what this graph represents" |
| Canonical graph state | Relix / Neo4j | Authoritative for entities, relations, resolutions, and evidence |

These are deliberately two different facts. They converge during bootstrap and may diverge during an ontology migration. Divergence is a pending migration signal, not a resolver failure.

## 2. Metadata schema

```cypher
(:CanonicalGraphMetadata {
  tenant_id: string,           // UNIQUE
  graph_id: string,            // always "canonical" in the single-graph model
  projection_version: string,  // the ontology version this graph was built against
  synced_at: datetime,         // last validation against Syntology
  created_at: datetime,
  updated_at: datetime
})
```

Constraint:

```cypher
CREATE CONSTRAINT canonical_graph_metadata_tenant_unique IF NOT EXISTS
FOR (m:CanonicalGraphMetadata) REQUIRE m.tenant_id IS UNIQUE;
```

Metadata **caches** the projection version; it does not call Syntology on every resolver read. This keeps canonical graph reads available during a Syntology outage. `synced_at` supports staleness reporting without making every query an ontology lookup.

`projection_version` is not `projectionGeneration`. Rebuild/snapshot identity is deferred (ADR-026).

## 3. Bootstrap path

Bootstrap is required only when all of the following are true:

1. There is no `:CanonicalGraphMetadata` for the tenant.
2. The tenant has existing canonical graph data.
3. Syntology returns an unambiguous active ontology version.

Procedure:

1. `Syntology.getActiveVersion(tenantId)` via the service-JWT lookup in §5.
2. `MERGE` the metadata node with `ON CREATE SET` for version/timestamps and `ON MATCH SET` updating only `updated_at`, never `projection_version`.
3. Rely on the unique constraint for concurrent bootstrap. If the constraint/merge race is observed, retry once; a second failure is `AmbiguousCanonicalGraphException`.
4. Return `GraphIdentity(tenantId, "canonical", metadata.projectionVersion)`.

Bootstrap never overwrites an existing `projection_version`. A version change is a separate ontology migration (out of scope).

## 4. Resolver semantics

```java
public interface GraphIdentityResolver {
    GraphIdentity resolveCanonical(String tenantId);
}
```

Exception surface:

| Condition | Exception | Operator action |
|---|---|---|
| No metadata and no canonical data | `MissingCanonicalGraphException` | Provision the tenant |
| Canonical data exists, no metadata, Syntology returns no active version | `OntologyVersionUnavailableException` | Bootstrap/activate an ontology first |
| Syntology lookup times out or fails | `OntologyLookupFailedException` (retryable) | Retry; do not fall back to a default |
| Unique constraint/race remains ambiguous | `AmbiguousCanonicalGraphException` | Investigate duplicate metadata |
| Adapter receives a non-canonical graphId | `UnsupportedGraphIdException` | Contract violation; multi-graph needs a new ADR |

Steady state: read metadata, return its identity, and do not call Syntology. If metadata version differs from Syntology's current active version, log a structured warning and return the metadata version. The migration workflow owns the update.

No resolver path may derive a version from `:RESOLVES_TO`, entity timestamps, or a most-common value.

## 5. Syntology → Relix lookup contract

Endpoint:

```http
GET /v1/tenants/{tenantId}/active-ontology-version
Authorization: Bearer <service JWT with tenant scope>
```

Success:

```json
{
  "tenantId": "tenant-a",
  "activeVersion": "v1.2.0",
  "activatedAt": "2026-11-11T00:00:00Z"
}
```

Failures:

- `404` — no active ontology for the tenant → `OntologyVersionUnavailableException`
- `503` / timeout — Syntology unavailable → `OntologyLookupFailedException` (retryable)
- `401` — service identity rejected → fail the bootstrap; operator intervention

No Relix-side cache beyond the metadata node. Bootstrap is one-time; steady-state reads metadata.

## 6. Failure taxonomy

The resolver must not collapse failures into one generic exception:

- **Missing tenant/canonical graph:** data admission problem.
- **No active ontology:** ontology provisioning problem.
- **Syntology unavailable:** infrastructure/retry problem.
- **Ambiguous metadata:** invariant violation.
- **Unsupported graphId:** contract violation.

Each maps to a distinct operator action and log code. The API layer must not convert an infrastructure failure into “graph not found.”

## 7. Worked example — existing tenant, no metadata

Tenant `tenant-a` already has canonical `:Entity`/`:REL` data but no metadata.

1. Resolver reads metadata: none.
2. Resolver checks canonical data: present.
3. Resolver calls Syntology:
   `GET /v1/tenants/tenant-a/active-ontology-version` → `200 {"activeVersion":"v1.2.0"}`.
4. Resolver executes:
   ```cypher
   MERGE (m:CanonicalGraphMetadata {tenant_id: 'tenant-a'})
   ON CREATE SET
     m.graph_id = 'canonical',
     m.projection_version = 'v1.2.0',
     m.synced_at = datetime(),
     m.created_at = datetime(),
     m.updated_at = datetime()
   ON MATCH SET m.updated_at = datetime()
   RETURN m.graph_id, m.projection_version
   ```
5. Returns `GraphIdentity("tenant-a", "canonical", "v1.2.0")`.
6. Subsequent calls read metadata only and do not call Syntology.

## 8. Failure example — canonical data, Syntology 404

Tenant `tenant-b` has canonical data and no metadata. Syntology returns:

```http
HTTP/1.1 404
{"code":"NO_ACTIVE_ONTOLOGY","tenantId":"tenant-b"}
```

Resolver result:

```text
GraphIdentityResolver.resolveCanonical("tenant-b")
  -> OntologyVersionUnavailableException
log: graph.identity.bootstrap.failed tenant=tenant-b reason=no_active_ontology
```

Operator action: activate/import the ontology for `tenant-b`, then retry bootstrap. The graph stays on its existing tenant data but is not exposed under a fabricated identity.

## 9. Out of scope

- Ontology version migration after bootstrap.
- Multiple canonical graphs per tenant.
- Historical/versioned projection inspection.
- `projectionGeneration` or snapshot/rebuild identity.
- Per-node `projection_version` backfill.
- Any code change in #618a–#618c until this contract is accepted.

## Acceptance

This contract is accepted when the ownership split, metadata cache, bootstrap merge, failure taxonomy, and service lookup are reviewed and merged. Gate A may then implement the Syntology lookup; Gate B the metadata/bootstrap; Gate C the resolver; Gate D the ports; Gate E the sweep.
