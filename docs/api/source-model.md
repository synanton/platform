# Source Resource Model

**Status:** Draft — #248 artifact ("minimal source ingestion lifecycle")
**Source:** Design 1.28 §7.5–§17, §39–§40, §112–§113 (ingestion lifecycle); Design 1.32 §29–§32 (API surface)

The resource model for sources, source versions, and ingestion runs. A
**projection** of Design 1.28 (ingestion lifecycle, authoritative) and Design
1.32 (API surface), both design-complete — not a new design. 1.32 §29 states
"Source lifecycle follows Design 1.28."

## Scope

#248's list, mapped:

| Ticket item | Source |
|---|---|
| stable source identity | 1.28 §10, 1.32 §8 |
| immutable versions | 1.28 §7.5, §10; 1.32 §30 |
| ingestion status | 1.28 §15; 1.32 §32 |
| idempotent re-ingestion | 1.28 §39–§40, §112–§113 |
| deletion / tombstones | 1.28 §16–§17 |

## Source identity

Source identity is a primary contract (1.28 §10). It distinguishes *which
logical source resource* produced an artifact:

```text
SourceIdentity {
    tenant_id
    connector_id
    source_id
    resource_id
}
```

`source_id` is a stable identifier (1.32 §8), not a database key, shard id, or
storage location (1.32 §8).

**Identity ≠ content digest.** Content digest identifies *what the acquired
bytes hash to* (SHA-256 over a precisely defined representation, 1.28 §11).
Digest equality **must not** imply source-resource identity — two resources
(`A/document.pdf`, `B/document.pdf`) may contain identical bytes and still be
distinct logical resources (1.28 §12). Digest is for storage dedup, integrity,
change detection, cache reuse — never for silently merging sources.

## Source version

A `SourceVersion` is identified separately from the source and is **immutable**
(1.28 §7.5): a previously acquired version must not silently change; a source
change creates a new version.

```text
SourceVersion {
    source_identity
    version_id
    content_digest
    source_revision
    observed_at
}
```

The version carries both `source_identity` and `content_digest` side by side —
neither substitutes for the other (1.28 §10–§11).

**Version detection (1.28 §13):** connectors expose the strongest reliable
signal available, preferred order: source-native revision → immutable object
version → ETag → modification timestamp + size → content digest →
connector-specific change token. Weak signals must not override stronger ones.
The connector declares its strategy.

## Ingestion run

An ingestion run is the resource a client submits and monitors (1.32 §31–§32):

```json
{
  "run_id": "ing_01...",
  "status": "RUNNING",
  "operation_id": "op_01...",
  "source_id": "src_01..."
}
```

`run_id` identifies the run; `operation_id` links it to the public Operation
(`operation-model.md`) for long-running work — the run resource and the
Operation are linked by stable IDs, not conflated.

**Synchronization result (1.28 §15):** a sync run produces an explicit
`SyncResult`:

```text
SyncResult {
    discovered
    changed
    unchanged
    deleted
    failed
    skipped
}
```

These states must not be collapsed into a generic success/failure — "checked,
nothing changed" is distinct from "could not be checked" is distinct from
"reported deletion".

## Deletion and tombstones

**Deletion is a first-class ingestion event** (1.28 §16). A resource
disappearing from a sync result does **not** mean it was deleted — the connector
must distinguish:

```text
DELETED · NOT_VISIBLE · NOT_FOUND · ACCESS_DENIED · SYNC_INCOMPLETE · UNKNOWN
```

This prevents an incomplete synchronization from causing destructive downstream
actions.

**Tombstones (1.28 §17):** where deletion is authoritative, ingestion produces a
durable tombstone retaining sufficient identity and provenance for downstream
systems to determine what was deleted:

```text
Source Resource → Tombstone → { Content Cache invalidation, Search projection removal, Knowledge invalidation, Analytics event }
```

## Idempotency

**Ingestion identity (1.28 §39):** every ingestion request has an idempotency
identity:

```text
tenant_id + source_id + resource_id + source_version
```

If the source exposes no reliable version, the connector uses an explicitly
documented change-detection strategy.

**Duplicate delivery (1.28 §40):** the same source version delivered multiple
times converges on **one** authoritative source version; attempts remain
observable.

**Idempotency keys (1.28 §112–§113):** API clients may supply explicit
idempotency keys, persisted for the retention period; repeated commands with the
same identity converge to the same logical operation. Keys are scoped
`tenant_id + idempotency_key` — a Tenant A key must not collide with or access
Tenant B's operation.

## API surface

1.32 §29–§32:

```http
POST   /v1/sources
GET    /v1/sources/{source_id}
PATCH  /v1/sources/{source_id}
DELETE /v1/sources/{source_id}
GET    /v1/sources

GET /v1/sources/{source_id}/versions
GET /v1/sources/{source_id}/versions/{version_id}

POST /v1/ingestion-runs
GET  /v1/ingestion-runs/{run_id}
POST /v1/ingestion-runs/{run_id}/cancel
```

Tenant scope is derived from trusted request context, not URL parameters
(1.32 §28) — see `identity-context.md`.

## Events

Source lifecycle transitions are events over the frozen 1.27 contract
(`eventing-contract.md`): `source.discovered`, `source.version.created`,
`source.deleted`, etc. The eventing contract does **not** enumerate event types
(`event_type` is an open envelope field, §13), so these are **additive** — no
contract revision needed. Deletion propagates as a tombstone event (§17).

## Freeze scope

This artifact freezes the resource model: identity, version, run, deletion, and
idempotency shapes. Implementation (the ingestion pipeline, connector version
detection, tombstone propagation) follows, against this model.

## Cross-references

- `docs/architecture/synanton-design-1.28.md` — authoritative ingestion lifecycle
- `docs/architecture/synanton-design-1.32.md` — authoritative API surface (§29–§32)
- `docs/api/operation-model.md` — the Operation an ingestion run links to
- `docs/api/eventing-contract.md` — the event contract source lifecycle uses
- `docs/api/identity-context.md` — tenant/principal source
- [ADR-005](../../architecture/decisions/adr-005-ingestion-plane.md) — the plane decision
