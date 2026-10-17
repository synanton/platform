# Source Resource Model

**Status:** Frozen 2026-10-17 — #248 source-model contract; implementation remains open under issue #248
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

For the API route `/v1/sources/{source_id}`, `source_id` is unique within the
trusted `tenant_id` scope. The full ingestion identity is finer-grained:
`tenant_id + connector_id + source_id + resource_id` (§10). The URL lookup key
does not replace the provenance identity.

The Source resource exposes the lookup identity and sync timestamps:

```json
{
  "source_id": "src_01...",
  "connector_id": "conn_01...",
  "resource_id": "drive-file-42",
  "created_at": "2026-10-17T10:00:00Z",
  "last_successful_sync_at": "2026-10-17T10:05:00Z"
}
```

`tenant_id` is bound from verified request context, not copied from a body
field. `last_successful_sync_at` is read-only and derived from the latest
successful sync run.

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
The `source_version` term in §39's idempotency identity denotes this model's
`version_id`; `source_revision` is the upstream version-detection signal used to
derive it, not an alternate name for the platform version ID.

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
  "status": "ACQUIRING",
  "operation_id": "op_01...",
  "source_id": "src_01...",
  "result": null
}
```

`run_id` identifies the ingestion run; `operation_id` links it to the public
Operation (`operation-model.md`) for long-running work — the two resources are
linked by stable IDs, not conflated. This model chooses the ingestion-specific
§38 vocabulary for `IngestionRun.status`, rather than mirroring generic
`Operation.status`. Accordingly, this projection uses `ACQUIRING` in the
example; the `RUNNING` value in the illustrative §32 response is not treated as
the run-state vocabulary. The ingestion-run states are:

```text
DISCOVERED · SCHEDULED · ACQUIRING · VALIDATING · PUBLISHED · COMPLETED
FAILED · CANCELLED · SKIPPED · UNCHANGED · DELETED · QUARANTINED
```

The state transitions are durable (§38). `Operation.status` remains the
cross-plane lifecycle; `IngestionRun.status` reports ingestion-specific state.

**Status naming divergence from §32:** that section's example says
`status: RUNNING`; this model selects the ingestion-specific §38 state
(`ACQUIRING`, `VALIDATING`, etc.) for `IngestionRun`. The linked Operation
carries the generic public Operation status.

**Terminal IngestionRun → Operation mapping:**

| IngestionRun terminal status (§38) | Operation status | Qualification |
|---|---|---|
| `COMPLETED`, `SyncResult.failed = 0`, `quarantined = 0` | `SUCCEEDED` | `outcome: FULL` |
| `COMPLETED`, at least one success and any failed/quarantined entries | `SUCCEEDED` | `outcome: PARTIAL` (ADR-020) |
| `FAILED` | `FAILED` | W2 error instance describes the failure; manifest/result preserve any completed prefix |
| `CANCELLED` | `CANCELLED` | `outcome` absent; run result/manifest preserve any completed prefix and cancelled remainder |
| `SKIPPED` / `UNCHANGED` / `DELETED` | `SUCCEEDED` | `outcome: FULL`; run status preserves the specific no-op/deletion result |
| `QUARANTINED` (no resource published) | `FAILED` | `retryable: false`; W2 `OPERATION_FAILED`, `details.reason: QUARANTINED`, `details.quarantined`; no artifact is published |

`outcome` is **present only on `SUCCEEDED`**; it is null/absent on `FAILED` and
`CANCELLED`. A cancelled Operation records the run outcome, not an assertion
that no work completed: successful resources remain in `IngestionRun.result`
and the manifest, with interrupted admitted resources recorded as `CANCELLED`.
Cancellation is a run-level terminal state; a `COMPLETED` run cannot have a
nonzero `SyncResult.cancelled` count. The `outcome` value describes completion
coverage, not whether new content was
created: an unchanged sync or authoritative deletion can be `SUCCEEDED`/`FULL`
while `IngestionRun.status` preserves `UNCHANGED` or `DELETED`. A `COMPLETED`
run with some successes and some failed/quarantined resources is
`SUCCEEDED`/`PARTIAL`. `FAILED` may occur after a successful prefix; its
Operation has no `outcome`, while `SyncResult` and the manifest preserve the
completed resources and the failure. If no resource succeeds, the run
terminates `FAILED` (§38), not `COMPLETED`.

The run-specific status remains available on `IngestionRun`; the linked
Operation exposes the cross-plane lifecycle. This mapping prevents a client
holding both IDs from having to infer how terminal ingestion outcomes map.

`outcome: PARTIAL` does not encode the cause of partiality. A client deciding
whether to retry must inspect `IngestionRun.result` for failed/quarantined
counts and consult the manifest for resource-level details; Operation status
alone is intentionally a coarse run-level summary.

`QUARANTINED` is not retried automatically: the artifact was acquired but
withheld for security, validation, malware, or policy concerns (§43). A
fully-quarantined run maps to `FAILED` with W2 `OPERATION_FAILED`,
`retryable: false`, and summary `details.reason: QUARANTINED` plus
`details.quarantined` (integer count, same name/value as
`SyncResult.quarantined`). For mixed-success runs, the `SUCCEEDED` Operation
uses `outcome: PARTIAL`; `IngestionRun.result.quarantined` gives the count. The
Operation detail is a summary, not the per-resource record.

**Synchronization result (1.28 §15):** a sync run produces an explicit
`SyncResult` in the `IngestionRun.result` member. It is `null` while the run is
active and populated when the run reaches a terminal state. The 1.32 public
summary extends 1.28 §15's six counters with `quarantined` and `cancelled`,
preserving the distinct per-resource outcomes in §38/§43. These are explicit
1.32 projection extensions, not claims that §15 already defines those counters:

```text
SyncResult {
    discovered
    changed
    unchanged
    deleted
    failed
    skipped
    quarantined
    cancelled
}
```

These states must not be collapsed into a generic success/failure — "checked,
nothing changed" is distinct from "could not be checked" is distinct from
"reported deletion". Each manifest entry has exactly one final result; the
counters are mutually exclusive and **sum to `IngestionManifest.entry_count`**:
`entry_count = discovered + changed + unchanged + deleted + failed + skipped + quarantined + cancelled`.

## Ingestion manifest

Design 1.28 §9.7 requires a durable description of resources discovered or
processed during a run, enabling reconciliation, auditing, incremental sync,
retry, and reporting. The design defines the semantic requirement, not a
record schema; this is the minimal #248 shape:

```text
IngestionManifest {
    manifest_id
    run_id
    generated_at
    entry_count
    entries_ref
}

ManifestEntry {
    source_identity
    result                 // DISCOVERED, CHANGED, UNCHANGED, DELETED, FAILED, SKIPPED, QUARANTINED, CANCELLED
    source_version_id?     // present when a version was created/observed
    error_ref?             // structured failure detail when applicable
}
```

There is **one manifest entry per source resource when first discovered by a
run**, recording that resource's final result, not one entry per transition or
attempt. Attempts stay observable separately (§40). Discovery creates the entry;
admission/processing updates its final result. A discovered resource interrupted
by cancellation receives `result: CANCELLED`; a resource not yet discovered has
no entry. Therefore `entry_count` equals the sum of the eight mutually exclusive
`SyncResult` counters.

The counters are final, mutually exclusive dispositions, not cumulative stage
totals. `DISCOVERED` is final only for a discovery-only run where the resource
was enumerated but not admitted for processing; once processing starts, the
entry's final result is `CHANGED`, `UNCHANGED`, `FAILED`, or another terminal
disposition.

An empty manifest (`entry_count = 0`, all counters zero) is valid and
interpreted with the terminal run state:

- `FAILED` with an empty manifest means the run failed before discovering any
  resource; the Operation/run error records why.
- `COMPLETED` or `UNCHANGED` with an empty manifest means the connector ran on
  an empty source and found nothing to admit.
- `SKIPPED` with an empty manifest means policy skipped the run before discovery;
  the connector was not invoked.
- `CANCELLED` with an empty manifest means cancellation arrived before any
  resource was discovered.
- An `Operation` in `EXPIRED` with an empty manifest and a nonterminal
  `IngestionRun` status means the operation expired before resource discovery;
  expiry is server policy, and does not add an `EXPIRED` state to the §38
  ingestion-run state machine.
- An authoritative deletion of a target source resource creates a `DELETED`
  manifest entry even when that resource has no prior versions, so its run
  manifest is not empty.

The terminal state plus the empty manifest distinguishes failed start, healthy
empty source, policy skip, and pre-discovery cancellation; none is an
unrecorded run.

The manifest is immutable after the run completes. `entries_ref` points to
the paged manifest-entry record, not an unbounded inline array. The
`entries_ref` target is immutable and retained at least as long as the manifest.
**Owner:** the Ingestion Plane owns this source-history metadata (not Content
Cache, which owns content artifacts). **Retention:** a manifest referenced by a
source version or tombstone, and its `entries_ref` target, must remain
resolvable at least as long as that reference is retained; source-version
metadata retention and legal hold govern its lifetime.

Here §9.7's **retry** means a subsequent attempt can consult the completed
manifest for reconciliation and deduplication. **In-progress resume after a
process failure is not provided by the manifest** — it uses durable workflow
state and step/idempotency records (Design 1.27 §41, §44, §97; #250). This
separates the run's final resource ledger from the workflow's live recovery
checkpoint.

**Operation lookup:** ingestion submission returns both `run_id` and
`operation_id`. The manifest is keyed by `run_id`; clients retain that ID from
the submission response. There is no direct manifest-by-Operation lookup in
this contract.

`error_ref` resolves to the structured W2 Problem detail for that manifest
entry. The manifest is authoritative for per-resource failure/quarantine
reasons. For a fully quarantined/failed Operation, the W2 error's `details`
carry the aggregate (`reason: QUARANTINED`, `quarantined` integer count); this is a
summary, not a per-resource record. For mixed-success runs, the Operation is
`SUCCEEDED/PARTIAL`, and `IngestionRun.result.quarantined` carries the aggregate
count while the manifest carries individual reasons.

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
Tombstone {
    source_identity        // tenant_id + connector_id + source_id + resource_id
    deleted_at
    deleted_by: {
        principal_id
        principal_type
    }
    version_count
    versions_manifest_ref  // reference to immutable version-ID manifest
}
```

This is the minimal #248 API tombstone shape selected to satisfy §17's
"sufficient identity and provenance" requirement. `deleted_by` uses the 1.29
principal namespace (including `CONNECTOR`), so the actor cannot be confused
with a connector ID in another namespace. Version IDs are kept in a referenced
manifest rather than an unbounded inline array. The Ingestion Plane owns the
manifest metadata; `versions_manifest_ref` must remain resolvable for at least
the tombstone's retention period (including applicable legal hold). Downstream
effects include
Content Cache invalidation, search-projection removal, knowledge invalidation,
and an analytics event.

There is no `deletion_state` field: tombstone existence itself means the
authoritative deletion state is `DELETED`; non-authoritative observations
(`NOT_VISIBLE`, `SYNC_INCOMPLETE`, etc.) do not produce tombstones.

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

**Two related idempotency identities, with distinct purposes:**

- **Ingestion-version dedup (§39–§40):** `tenant_id + source_id + resource_id
  + source_version` identifies one logical source version; repeated delivery
  converges on one authoritative version.
- **Client command key (§112–§113; 1.32 §23–§25):** API clients may supply an
  idempotency key, persisted for its retention period. Scope it by
  `tenant_id + principal_id + endpoint + idempotency_key` (the 1.32 default);
  §113's `tenant_id + idempotency_key` is the minimum cross-tenant isolation
  constraint, not the complete API scope. A stronger domain-specific identity
  may be used where required.

These are not competing keys: the first deduplicates source versions, the
second deduplicates client commands/operations.

## Composition with the eventing layer

Source-version idempotency (§39–§40) and event-delivery idempotency
(`processed_events`, Eventing 1.27 §24–§25) protect different boundaries:

- A repeated source version converges on the existing version and is reported
  as `UNCHANGED`; it does not create another `SourceVersion`.
- A request retry with the same idempotency key converges on the same logical
  Operation (1.32 §23–§25); a deliberate new run has a new `run_id` but retains
  the same source-version identity for deduplication.
- Event redelivery keeps the same `event_id`; a deliberate replay is governed
  by the event type's replay policy (Eventing 1.27 §38–§39). Source consumers
  also use the source business-key companion record
  `processed_source_versions` described in `eventing-contract.md` when a replay
  uses a new event ID.

**Outbox publication order (#250):** Content Cache first completes 1.28 §20's
verified, atomic artifact-publication sequence. After the artifact is durable,
the Ingestion Plane commits the authoritative `SourceVersion` published state
and its `SourcePublished` outbox row in the same local transaction; the
dispatcher emits only after that commit. No transaction spanning Content Cache
and the Ingestion Plane is implied.

- Crash after Content Cache publication but before the Ingestion Plane commit:
  an orphaned content-addressed artifact may remain, but there is no published
  SourceVersion and no `SourcePublished` event; reconciliation can disposition
  the orphan.
- Crash after the version/outbox transaction but before dispatch: the outbox
  retries on restart, with the referenced artifact already durable.

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

### Source resource fields and mutability

The Source API's `PATCH` surface is deliberately limited:

- **Mutable:** `source_endpoint` and `capability_flags` (non-secret static
  connector configuration; 1.28 §75).
- **Immutable:** `tenant_id`, `connector_id`, `connector_type`, `source_id`,
  `resource_id`, and all published `SourceVersion` identity/content fields.
- **Not patched here:** credentials/tokens/certificates (secret configuration),
  schedule/concurrency/retry policy (runtime policy), and retention/classification/
  quotas (tenant policy). These are separately governed configuration. Changes
  affecting ingestion behavior are versioned and recorded on the run (§75–§76).

Changing `source_endpoint` must not silently change logical source identity.
On PATCH, the connector re-resolves `source_id` and `resource_id`; if the new
endpoint identifies a different logical origin/resource, create a new source
identity/history rather than appending versions from the different origin to
the existing identity.

Source-level timestamps (read-only resource metadata selected for #248):
`created_at` records resource creation; `last_successful_sync_at` is projected
from the latest successful sync run. It anchors `SYNC_INCOMPLETE` assessment
against the previous successful observation. `SourceVersion.observed_at`
remains the observation time for each individual version (§10).

Source-list pagination is out of scope for this #248 model; pagination
conventions are owned by W7. This artifact defines no source-list cursor or
continuation-token behavior.

## Events

Source lifecycle transitions use the recommended event names from 1.28 §44,
over the frozen 1.27 envelope (`eventing-contract.md`): `SourceDiscovered`,
`SourceChanged`, `SourceUnchanged`, `SourceDeleted`, `IngestionStarted`,
`SourceAcquired`, `SourceValidated`, `SourcePublished`, `IngestionCompleted`,
`IngestionFailed`, `SourceQuarantined`. The eventing contract does **not**
enumerate event types (`event_type` is an open envelope field, §13), but the
1.28 names are the authority for this plane — do not substitute dotted/lowercase
aliases without recording a deliberate divergence. **`SourceDeleted` is the
deletion event; the tombstone is its payload/durable record, not a second event
type** (§17).

## Freeze scope

This artifact freezes the resource model: identity, version, ingestion-run
status, deletion states and tombstone shape, idempotency identities, the mutable
Source PATCH surface, and source timestamps. Implementation (the ingestion
pipeline, connector version detection, tombstone propagation) follows against
this model.

## Cross-references

- `docs/architecture/synanton-design-1.28.md` — authoritative ingestion lifecycle
- `docs/architecture/synanton-design-1.32.md` — authoritative API surface (§29–§32)
- `docs/api/operation-model.md` — the Operation an ingestion run links to
- `docs/api/eventing-contract.md` — the event contract source lifecycle uses
- `docs/api/identity-context.md` — tenant/principal source
- [ADR-005](../../architecture/decisions/adr-005-ingestion-plane.md) — the plane decision
