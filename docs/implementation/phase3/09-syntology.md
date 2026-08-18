# 09 - syntology - Phase 3 - Session Pinning, Per-Tenant Versioning, Capability Matrix

**Version:** 1.0
**Date:** 2026-07-24
**Status:** Draft for review
**Depends on:** `syntology` Phase 2 DoD met; `security` Phase 3 `TenantContextFilter` providing `tenantId` from JWT; PostgreSQL `syntology` schema from Phase 2
**Scope:** Session pinning (pin a version for 24 h), per-tenant ontology version namespace, capability matrix additions. No new external dependencies.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/platform/synanton-design-1.19.md) §21 `syntology` (ontology versioning, session pinning, per-tenant namespacing, capability matrix) | Production target. Phase 3 adds session pinning and tenant namespacing on top of the Phase 2 single-tenant baseline. |
| [syntology Phase 2](../phase2/07-syntology.md) | Foundation. Phase 2 delivered `GET/POST /api/v1/ontology/entities`, `POST /api/v1/ontology/entities/resolve`, and single-tenant (`demo`) versioning. |
| [06-security Phase 3](./06-security.md) | `TenantContextFilter` extracts `tenantId` from `SubjectAssertion` and populates `RequestContextHolder`. `syntology` reads `tenantId` from `RequestContext` on every request. |

**Explicit non-goals for Phase 3:**

- No cross-tenant ontology inheritance - each tenant's namespace is fully isolated.
- No ontology diff/merge tooling - Phase 5.
- No per-entity ACL - all entities in a tenant's namespace are readable by any authenticated caller with that tenant's token.
- Session pins are per-user (`sub` from JWT), not per-device or per-session-token.
- TTL expiry deletes pin metadata only - it does not affect the underlying versioned ontology data.

---

## 2. Phase 3 in One Sentence

> Partition the ontology version namespace by `(tenant_id, version)`, add session pinning so a caller can lock all their reads to a specific version for 24 hours, and expose `SESSION_PINNING=NATIVE` and `MULTI_TENANT_VERSIONING=NATIVE` in the capability matrix.

---

## 3. Target Architecture

```mermaid
flowchart TD
  REQ[Authenticated request + tenantId from JWT] --> TCF[TenantContextFilter\nsets RequestContext]
  TCF --> PIN[SessionPinResolver\ncheck active pin for caller sub]
  PIN -->|pinned version| VER[version = pinned_version]
  PIN -->|no pin| VER2[version = latest for tenant]
  VER --> ENT[OntologyEntityStore\nSELECT WHERE tenant_id=? AND version=?]
  VER2 --> ENT
  ENT --> DB[(PostgreSQL\nsyntology schema)]
  ADMIN[caller] -->|POST /session-pin| PINSTORE[session_pins table]
  TTL[TTLWorker\n@Scheduled 5 min] -->|DELETE expired| PINSTORE
```

---

## 4. Data Contracts

### 4.1 `POST /api/v1/ontology/session-pin`
Request:
```json
{ "tenant_id": "demo", "version": "v2.1.0" }
```
Response (HTTP 201):
```json
{
  "pin_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "tenant_id": "demo",
  "version": "v2.1.0",
  "subject_id": "user:alice",
  "pinned_at": "2026-07-24T10:00:00Z",
  "expires_at": "2026-07-25T10:00:00Z"
}
```

### 4.2 `GET /api/v1/ontology/session-pin`
Response (HTTP 200 if pinned, 404 if not pinned):
```json
{
  "pin_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "tenant_id": "demo",
  "version": "v2.1.0",
  "expires_at": "2026-07-25T10:00:00Z",
  "remaining_seconds": 86400
}
```

### 4.3 `GET /api/v1/ontology/versions?tenantId=demo2`
Response:
```json
{
  "tenant_id": "demo2",
  "versions": ["v1.0.0", "v1.1.0"],
  "latest": "v1.1.0"
}
```

### 4.4 Capability matrix (extended)
```json
{
  "capabilities": {
    "ONTOLOGY_VERSIONING": "NATIVE",
    "ENTITY_RESOLUTION": "NATIVE",
    "SESSION_PINNING": "NATIVE",
    "MULTI_TENANT_VERSIONING": "NATIVE",
    "CROSS_TENANT_INHERITANCE": "NOT_SUPPORTED",
    "ONTOLOGY_DIFF": "NOT_SUPPORTED"
  }
}
```

---

## 5. Implementation Design

### 5.1 Per-tenant versioning - schema changes

`syntology` Phase 2 stored versions with a single `version` column. Phase 3 makes the version namespace `(tenant_id, version)`.

Flyway `V2__multi_tenant.sql`:
```sql
-- Add tenant_id if not already present (Phase 2 may have added it; this is additive)
ALTER TABLE syntology.ontology_versions
  ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

ALTER TABLE syntology.entity_types
  ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ontology_versions_tenant_version
  ON syntology.ontology_versions (tenant_id, version);

CREATE INDEX IF NOT EXISTS idx_entity_types_tenant_version
  ON syntology.entity_types (tenant_id, version);
```

All `OntologyEntityStore` queries gain a `WHERE tenant_id = :tenantId` clause. The `tenantId` comes from `RequestContext.tenantId()`.

`OntologyVersionService.getLatest(tenantId)` returns the highest version for the given tenant. In Phase 2 this was always `demo`; Phase 3 makes it a proper parameter.

### 5.2 `session_pins` table

Flyway `V3__session_pins.sql`:
```sql
CREATE TABLE syntology.session_pins (
  pin_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  TEXT NOT NULL,
  version    TEXT NOT NULL,
  subject_id TEXT NOT NULL,
  pinned_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  UNIQUE (tenant_id, subject_id)   -- one active pin per user per tenant
);
CREATE INDEX ON syntology.session_pins (tenant_id, subject_id) WHERE expires_at > now();
```

The `UNIQUE (tenant_id, subject_id)` constraint means a new `POST /session-pin` replaces the existing pin (upsert: `INSERT ... ON CONFLICT (tenant_id, subject_id) DO UPDATE SET version=EXCLUDED.version, pinned_at=now(), expires_at=EXCLUDED.expires_at`).

### 5.3 `SessionPinResolver`

`SessionPinResolver` is a `@Component` called by every ontology read endpoint before querying `OntologyEntityStore`. It:
1. Reads `tenantId` and `subjectId` from `RequestContext`.
2. Queries `session_pins WHERE tenant_id=? AND subject_id=? AND expires_at > now() LIMIT 1`.
3. If found: return the pinned `version`.
4. If not found: return `OntologyVersionService.getLatest(tenantId)`.

Results are cached in a `Caffeine` cache keyed by `(tenantId, subjectId)` with TTL=30 s (balance between pin-change responsiveness and DB query volume).

### 5.4 Session pin endpoints

**`POST /api/v1/ontology/session-pin`:**
- Validate `version` exists for the `tenant_id` (query `ontology_versions`). If not: 400 Bad Request.
- Upsert `session_pins` with `expires_at = now() + 24h`.
- Invalidate the `SessionPinResolver` Caffeine cache for `(tenantId, subjectId)`.
- Return 201.

**`GET /api/v1/ontology/session-pin`:**
- Query `session_pins WHERE tenant_id=? AND subject_id=? AND expires_at > now()`. If not found: 404.
- Return pin details including `remaining_seconds = ChronoUnit.SECONDS.between(now(), expires_at)`.

**`DELETE /api/v1/ontology/session-pin`:**
- Delete the pin row. If not found: 404.
- Invalidate cache.
- Return 200.

### 5.5 TTL worker

```java
@Scheduled(fixedDelay = 300_000)
public void deleteExpiredPins() {
    int deleted = sessionPinRepository.deleteByExpiresAtBefore(Instant.now());
    log.info("Deleted {} expired session pins", deleted);
}
```

JPQL: `DELETE FROM SessionPin p WHERE p.expiresAt < :now`. Uses Spring Data JPA `@Modifying @Query`.

### 5.6 Capability matrix

`GET /api/v1/ontology/capabilities` - existing endpoint from Phase 2. Phase 3 adds two entries:
- `SESSION_PINNING: "NATIVE"` - pin-and-resolve is built-in, not an external feature.
- `MULTI_TENANT_VERSIONING: "NATIVE"` - version namespace is `(tenant_id, version)`.

The response is a static `@Bean` map updated at each phase - no dynamic capability discovery.

### 5.7 `POST /api/v1/ontology/entities/resolve` - pin awareness

Phase 2 introduced this endpoint (entity label → typed entity). Phase 3 change: before querying, call `SessionPinResolver.resolve(tenantId, subjectId)` to get the version. Pass that version to the entity store query. No API signature change.

---

## 6. Module Boundaries

| Module | Owns in Phase 3 | Does not own |
|--------|----------------|--------------|
| `syntology` | `session_pins` table, `SessionPinResolver`, session-pin endpoints, per-tenant version queries, capability matrix additions, TTL worker | JWT decoding (security), `tenantId` extraction (shared/common `TenantContextFilter`) |
| `shared/common` | `RequestContext` with `tenantId`, `subjectId` | Session pin storage |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|--------|------|-------|
| 1 | `syntology` Phase 2 DoD met - entity types, versioning, resolve endpoint working. | - | Non-negotiable. |
| 2 | `TenantContextFilter` from `shared/common` Phase 3 integrated into `syntology` filter chain. | `01-ingestion-pipeline` Phase 3 | Provides `RequestContext.tenantId()` and `subjectId()`. |
| 3 | PostgreSQL `syntology` schema has `tenant_id` columns (V2__ migration). | This plan V2__ | V2 may partially exist from Phase 2 - V2__ is idempotent (`ADD COLUMN IF NOT EXISTS`). |
| 4 | Flyway migrations V2__ and V3__ applied in sequence. | `syntology` app startup | Standard Flyway; no manual step. |
| 5 | `demo2` tenant seeded in `topology.tenants` (from `07-topology` V3 migration). | `07-topology Phase 3` | `syntology` creates ontology versions per tenant on first `POST /entities`. |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|------|-------------|------|
| SYN3-1 | Write Flyway `V2__multi_tenant.sql` (tenant_id columns + unique index); verify idempotent apply. | Migration file | 0.5 day |
| SYN3-2 | Write Flyway `V3__session_pins.sql` (session_pins table + index). | Migration file | 0.5 day |
| SYN3-3 | Update all `OntologyEntityStore` queries to include `WHERE tenant_id = :tenantId`; update service layer to pass `tenantId` from `RequestContext`. | DAO + service updates + tests | 1.5 days |
| SYN3-4 | Implement `SessionPinRepository` (Spring Data JPA); `TTLWorker`; `@Scheduled` cleanup. | Repository + worker + unit tests | 1 day |
| SYN3-5 | Implement `SessionPinResolver` with Caffeine cache; unit tests (pinned version returned, fallback to latest). | Class + tests | 1 day |
| SYN3-6 | Implement `POST`, `GET`, `DELETE /api/v1/ontology/session-pin` endpoints. | 3 endpoints + tests | 1.5 days |
| SYN3-7 | Wire `SessionPinResolver` into `OntologyEntityStore` and `entities/resolve` endpoint. | Wiring + integration test | 0.5 day |
| SYN3-8 | Update capability matrix response with `SESSION_PINNING` and `MULTI_TENANT_VERSIONING`. | Config update + test | 0.5 day |
| SYN3-9 | Integration test `SessionPinIT`: pin version `v1.0.0`, add entity to `v2.0.0`, assert pinned session does not see the new entity. | `SessionPinIT` | 1 day |
| SYN3-10 | Integration test `MultiTenantVersionIT`: create version `v1.0.0` for `demo` and `v1.0.0` for `demo2` with different entities; assert each tenant sees only their entities. | `MultiTenantVersionIT` | 1 day |

---

## 9. Testing Strategy

- **Unit:** `SessionPinResolver` with mock repository (pinned and non-pinned cases). `TTLWorker` with a mock repository asserting `deleteByExpiresAtBefore` is called. `OntologyEntityStore` with mock queries asserting `tenant_id` filter.
- **Integration:** `SessionPinIT` (Testcontainers Postgres): pin → add entity to newer version → assert pin still resolves old version. `MultiTenantVersionIT`: two tenants, same version name, different entity sets.
- **E2E:** Phase 3 demo: `demo` and `demo2` each resolve `ProductType` entity from their own version namespace - results differ.
- **Regression:** Phase 2 entity resolution tests pass unchanged when called with `demo` tenant token.

---

## 10. Configuration Surface

```yaml
# syntology/src/main/resources/application-phase3.yaml
syntology:
  session-pin:
    ttl-hours: 24
    cleanup-interval-ms: 300000
    resolver-cache-ttl-s: 30
    resolver-cache-max-size: 5000
  multi-tenant:
    default-tenant: demo   # used only for Phase 2 backward compat on unauthenticated tests
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|------|------------|----------|
| Caffeine cache TTL=30 s means a pin delete takes up to 30 s to propagate. | Cache is invalidated on `DELETE /session-pin` - not just TTL-expired. Immediate invalidation on delete. | Immediate invalidation on delete already implemented (§5.4). |
| `UNIQUE (tenant_id, subject_id)` on `session_pins` means a user can only pin one version per tenant. | This is by design - one pin per user per tenant is the intended UX. Users who need multiple pins should use different API keys. | By design. |
| TTL worker runs in the same JVM as the HTTP server - if the JVM is under heavy load, TTL cleanup may be delayed. | Expired pins are filtered at query time (`expires_at > now()`) - they are invisible even before the TTL worker deletes them. Cleanup is a space-reclamation step only. | No correctness risk. |
| V2__ migration alters `ontology_versions` which may have existing data in Phase 2. | `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` with `DEFAULT 'demo'` backfills existing rows safely. | Safe migration. |

---

## 12. Definition of Done (Phase 3)

1. `POST /api/v1/ontology/session-pin` creates a pin; `GET /api/v1/ontology/session-pin` returns the pin with non-zero `remaining_seconds`.
2. Subsequent `GET /api/v1/ontology/entities?tenantId=demo` uses the pinned version - verified by adding an entity to a newer version and asserting the pinned session does not return it.
3. `DELETE /api/v1/ontology/session-pin` removes the pin; subsequent entity resolution uses the latest version.
4. `SessionPinIT` and `MultiTenantVersionIT` pass in CI.
5. `GET /api/v1/ontology/versions?tenantId=demo2` returns only `demo2` versions, none from `demo`.
6. `GET /api/v1/ontology/capabilities` includes `SESSION_PINNING: "NATIVE"` and `MULTI_TENANT_VERSIONING: "NATIVE"`.
7. Expired pins (manually set to `expires_at = now() - 1s`) are cleaned up by the TTL worker within 5 minutes.
8. Phase 2 `syntology` endpoint regression tests pass unchanged (callers using `demo` tenant see no behavioral change).

---

## 13. Follow-on Phases (Signposted)

- **Phase 4** - Session pinning extended to multi-turn query sessions: pin persists across a conversation session, not just 24 h; pinned by `session_id` header.
- **Phase 4** - Cross-tenant ontology inheritance: `demo2` can inherit from `demo`'s base ontology version with override layers.
- **Phase 5** - Ontology diff API: `GET /api/v1/ontology/diff?from=v1.0.0&to=v2.0.0&tenantId=demo` returns added, removed, changed entities.
- **Phase 5** - Ontology merge: pull-request-style merging of entity sets from a staging version into production.
