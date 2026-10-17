# Eventing 1.27 Contract

**Status:** Frozen 2026-10-17 — #247 artifact ("Freeze Eventing 1.27 delivery and workflow contract")
**Source:** Design 1.27 (§8–§19 event/command semantics, §20–§30 delivery/outbox/inbox, §31–§32 retry, §33–§34 dead letters, §35–§37 poison/backpressure/retention, §38–§39 replay, §40–§45 workflow, §47/§53 Equalix role, §73–§76 failure, §97 persistence, §104 phases)

The frozen contract for the platform's common asynchronous execution fabric.
This is a **projection** of Design 1.27, which is design-complete (2697 lines,
111 sections) — not a new design. It freezes the shapes every plane (1.26,
1.28–1.31) integrates with rather than re-deriving.

## Scope

#247's list, mapped to the design:

| Ticket item | Design source |
|---|---|
| event/command semantics | §8–§19 |
| delivery guarantees | §23 |
| ordering | §22 |
| idempotency | §24–§25 |
| retry and backoff | §31–§32, §73–§76 |
| dead letters | §33–§34 |
| poison events, backpressure, retention | §35–§37 |
| durable workflow state | §41–§45, §97 |
| replay and replay safety | §38–§39 (policy source; classifications not enumerated here) |
| recovery | §76, §97 |

## Event

**Identity (§9):** every event has a globally unique `event_id`, stable across
retries. Republishing a logical event after a delivery failure must not mint a
new identity.

**Envelope (§13):**

```yaml
event_id: ...
event_type: ...
event_version: ...
occurred_at: ...
producer:
  service: ...
  version: ...
tenant:
  id: ...
correlation_id: ...
causation_id: ...
security:
  classification: ...
  policy_version: ...
payload: {}
```

**Tenant scope (§14):** every tenant-scoped event carries an explicit
`tenant_id`. Platform-wide events use `tenant_id: system` and are accessible
only to authorized platform operators.

**Immutability (§6.3):** events are immutable facts. Events communicate durable
facts; commands request work (§ executive summary).

**Security metadata is a claim, not a grant (§15).** A consumer must not assume
"internal event = trusted event." Authorization remains mandatory at the
processing boundary; `security.classification` and `policy_version` describe
what the producer asserted, not what the consumer may serve. See ADR-019.

## Command

**Identity (§17):** every command has a `command_id`; retries reuse the same
identity.

**Result (§18):** a command does not return the final business result for async
work — it returns `accepted` + an `operation_id`, then events carry completion.
This is the same shape as the public Operation model
(`docs/api/operation-model.md`); the internal command and the external Operation
are linked by stable IDs, not conflated.

## Ordering

**Entity ordering, not global (§22).** Global ordering is distinguished from
entity ordering; only entity ordering is normally required. Example: events for
a given document preserve order if the consumer depends on it.

## Delivery

**At-least-once (§23).** Consumers must not rely on exactly-once broker
delivery. At-least-once delivery + idempotent consumption gives effectively-once
business behavior (§6.5).

## Idempotency

**Consumer idempotency (§24–§25):** a consumer persists processed event
identity:

```text
processed_events
---------------
consumer_id
event_id
processed_at
result
```

Pattern: receive → check `event_id` → seen: ignore; unseen: process → record.
State change and idempotency record should be coordinated in the same
transaction where possible (**mandatory for source-lifecycle consumers; see
Business key vs event identity**).

### Business key vs event identity

**Composition clarification (#250, 2026-10-17; no change to the generic
`processed_events` shape).** `processed_events (consumer_id, event_id)` answers
"have I handled this event?" It does not answer "have I already applied this
source version?" Those are different identities:

| Identity | Prevents |
|---|---|
| `event_id` | double-handling the same event delivery |
| Source business key | double-processing the same source version when deliberately re-emitted under a new event ID |

The business key depends on the source-event class:

| Event class | Business key for replay/dedup |
|---|---|
| `SourceChanged`, `SourcePublished`, `SourceUnchanged` | `(tenant_id, source_id, resource_id, version_id)` — §39 source-version identity |
| `SourceDeleted` | `(tenant_id, connector_id, source_id, resource_id, deleted_at)` — tombstone identity; event carries tombstone/ref |
| `IngestionStarted`, `IngestionCompleted`, `IngestionFailed` | `(tenant_id, run_id, event_type)` |
| `SourceDiscovered`, `SourceAcquired`, `SourceValidated`, `SourceQuarantined` | `(tenant_id, run_id, source_identity, event_type)` |

In §39's idempotency tuple, `source_version` means this model's
`SourceVersion.version_id`; `source_revision` is the upstream detection signal,
not the platform version ID (`docs/api/source-model.md`). `connector_id` is
not in the §39 version key because `source_id` is unique within a tenant; the
deletion key includes the full SourceIdentity plus the tombstone timestamp.
`SourceChanged`, `SourcePublished`, and `SourceUnchanged` payloads carry
`source_identity` and `version_id`; a consumer cannot derive the version key
otherwise.
When emitted, `SourceUnchanged` carries the existing `version_id`, not a new
one. A `SourceDeleted` event carries the tombstone (or its
`versions_manifest_ref`); a consumer applies the tombstone only to the version
IDs in that manifest. A replayed deletion reuses the tombstone's original
`deleted_at`, not the replay time. If the manifest cannot be resolved, a
consumer may fall back to applying the tombstone to versions of that
`source_identity` whose `observed_at <= deleted_at`; versions observed after the
tombstone must never be affected. If neither manifest nor version timestamps
are available, the consumer must not apply the deletion; the missing manifest
is logged/reconciled, not treated as permission to delete the current source
wholesale.

Consumers maintain source-specific companion records, not source columns on
generic `processed_events`:

```text
processed_source_versions
-------------------------
consumer_id
tenant_id
source_id
resource_id
version_id                 // §39 source_version; canonical SourceVersion ID
processed_at

processed_source_deletions
--------------------------
consumer_id
tenant_id
connector_id
source_id
resource_id
deleted_at
processed_at
```

`SourceDeleted` consumers key by the tombstone's stable `deleted_at` and apply
the tombstone only to the version IDs in its manifest, never by deleting the
current source identity wholesale. A replay of an old tombstone therefore
cannot delete a recreated source. Run-level events use the run-scoped keys in
the table above and durable workflow state.

Both checks compose: `processed_events` suppresses redelivery of the same
`event_id`; the event-class business key suppresses duplicate downstream effects
when a durable fact is deliberately replayed under a new event ID. The source
model is authoritative for these keys; replay eligibility remains governed by
the event type's §39 policy.

**Storage choice:** the companion records are consumer-owned; the generic
`processed_events` shape above is unchanged. Their retention is at least as
long as the referenced source version or tombstone. A consumer checks and
records the relevant business key before applying downstream effects.

**Consumer atomicity for #250:** the local business effect and its corresponding
`processed_events` or source-business-key record must commit in one transaction
in the consumer's store. If the effect crosses a store/service boundary, write
an outbox intent in that same transaction and require idempotent application
downstream. This narrows §24's general "where possible" guidance for the
restart-recovery acceptance criterion; it does not claim a cross-service
transaction.

**Replay policy (Design 1.27 §38–§39):** replay applies only to durable facts
from which consumers can safely reconstruct state; it is not re-execution of
the original command. Replay eligibility is declared per event type in §39 and
is **not enumerated here**. Consumers obtain `REPLAY_SAFE`,
`REPLAY_WITH_POLICY`, or `NOT_REPLAYABLE` from the event-type contract/schema;
they must not infer replayability from the event name. This artifact references
the classification vocabulary, not a general replay procedure.

## Outbox and inbox

#250's "restart recovery" and "resume without rerunning committed effects"
criteria depend on the outbox and inbox patterns, so their record shapes are
**frozen here** rather than deferred — the design provides them (§28, §30).

**Outbox (§28):**

```text
outbox_events
-------------
event_id
event_type
event_version
tenant_id
aggregate_type
aggregate_id
payload
created_at
published_at
attempt_count
last_error
```

**Inbox (§30):**

```text
inbox_events
------------
consumer_id
event_id
received_at
processed_at
status
```

**Cross-plane boundary:** the *shapes* are frozen so consumers and producers
agree on the record contract. The *store* is per-service — each service owns
its outbox/inbox table (§97 PostgreSQL recommended, not required). The contract
is the column set and semantics, not a shared database.

**Relationship to `processed_events` (§24–§25).** Both are `(consumer_id,
event_id)`-keyed and they coexist as two phases of deduplication, not two
competing shapes:

- `inbox_events` deduplicates at **intake** — an event received but not yet
  processed.
- `processed_events` records **completion** with the `result` — an event whose
  business effect is committed.

A consumer needing only single-phase dedup may use one; a consumer that must
resume without rerunning committed effects (#250) uses both: the inbox stops
double-intake, the processed record proves completion across restart. They
differ in `status`/`result` and the intake-vs-completion timestamp because they
answer different questions.

## Retry

**Bounded exponential backoff (§31):** retryable failures use bounded
exponential backoff (e.g. 1s, 2s, 4s, 8s, 16s…), and a maximum retry policy
must exist — retry is bounded, not unbounded.

**Retry classification (§32):** errors are classified, and only appropriate
categories are retried:

```text
TRANSIENT · PERMANENT · SECURITY · INVALID · DEPENDENCY · TIMEOUT · RESOURCE
```

### Retry composition across layers

**Composition clarification (#250, 2026-10-17; no envelope or generic
`processed_events` shape change).** Request retry, ingestion-run retry, and
workflow-step retry have distinct identities:

| Layer | Mechanism | Identity |
|---|---|---|
| **Request retry** | Client re-issues the same HTTP request | `Idempotency-Key`, scoped `tenant + principal + endpoint + key` (1.32 §25) |
| **Run retry** | Operator/policy starts a new ingestion run | New `run_id`; same source-version identity is deduplicated by the source model |
| **Step retry** | Workflow redelivers after a retryable failure | Same `event_id` across delivery retries (§9) |

- A request retry with the same key converges on the original logical Operation
  (1.32 §23–§24); it does not start another run.
- A deliberate new run is not an HTTP retry. If the source version already
  exists, the source model records `UNCHANGED` and creates no duplicate version.
  A `SourceUnchanged` fact may record the new observation per 1.28 §44; it is not
  a duplicate `SourceChanged`/`SourcePublished` event.
  A full retry may re-observe O(resource_count) resources and emit one
  `SourceUnchanged` fact per unchanged resource; incremental connectors can
  bound that work with their durable synchronization cursor.
- A step retry/redelivery retains the same `event_id`; `processed_events`
  suppresses duplicate handling. Intentional event replay is distinct from
  retry (§38–§39). If a replay emits a new `event_id` for an existing source
  version, the source business-key check above prevents duplicate effects.

## Dead letters

**After retry exhaustion (§33):** the event moves to a dead-letter record that
**retains the original event identity**. Operators must be able to inspect,
classify, and act on dead letters (§34). Dead letters are not silent drops — the
identity is preserved so the event can be replayed or dispositioned.

## Workflow

A workflow is a **durable state machine** coordinating multiple operations
(§40). State must be durable (§41) and recoverable independently of the workflow
process (§97).

**State (§41):**

```text
workflow_id
workflow_type
workflow_version
tenant_id
state
created_at
updated_at
started_at
completed_at
current_step
correlation_id
failure_code
failure_message
```

**Identity (§42):** a workflow is identified by `workflow_id`, independent of
individual commands and events. One workflow may create many commands and
consume many events.

**Version (§43):** workflow definitions are versioned (`IngestionWorkflow:v1`).
Existing executions continue under their original version unless explicitly
migrated.

**Steps (§44):** each step defines `step_id`, `command`, `input`, `expected
events`, `timeout`, `retry policy`, `compensation`.

**State machine (§45):** modeled explicitly — states and the events that
transition between them. Example (document-ingestion): `RECEIVED →
EXTRACTING → CACHED → CLASSIFIED → ANNOTATED → COMPLETED`, with
`EXTRACTION_FAILED → FAILED`.

## Failure and recovery

**Consumer failure (§75):** if a consumer fails after receiving but before
durable completion, the event is redelivered; idempotency prevents duplicate
business effects.

**Workflow failure (§76):** workflow state remains durable; a failed workflow is
one of `RETRYABLE` · `FAILED` · `CANCELLED` · `WAITING` · `ESCALATED`. This
distinguishes retryable from terminal — the property #250's acceptance criteria
require.

**Mapping to the public Operation state.** The internal command/workflow is
linked to the external Operation by stable IDs (§18), but the two failure
vocabularies must not be left to each consumer to reconcile. Three of the five
workflow failure states have no public counterpart; the mapping is:

| Workflow failure (§76) | Public Operation (`operation-model.md`) | Note |
|---|---|---|
| `RETRYABLE` | `RUNNING` | retry is progress, not failure |
| `WAITING` | `RUNNING` | blocked on a dependency; not client-actionable |
| `FAILED` | `FAILED` | terminal |
| `CANCELLED` | `CANCELLED` | terminal |
| `ESCALATED` | `FAILED` | terminal; operator intervention required — `error.code` distinguishes |

Without this table the first #250 implementer and the second consumer pick
different mappings for `RETRYABLE`/`WAITING`/`ESCALATED` — the divergence class
the platform reconciles elsewhere (`ExtractionStatus`, GPU `ExecutionState`).

**Persistence (§97):** workflow state must be recoverable independently of the
process. PostgreSQL is a recommended initial implementation, not a Platform
requirement.

## Correlation and causation

Every event envelope carries `correlation_id` and `causation_id` (§13), and
workflow state carries `correlation_id` (§41). These connect source, operation,
workflow, artifact, and processing run across service boundaries — the
cross-plane "what triggered this?" property.

## Equalix's role

Equalix is a **workflow participant**, not the engine. The Workflow Plane is
the fabric and the durable state owner (§47: "requires a durable state owner.
The Workflow Plane provides that owner"); Equalix executes as a participant
coordinating recalculation (§53). The generic Workflow Plane must not duplicate
Equalix's dependency-analysis logic (§53). This resolves the capstone's
"Workflow / Equalix" box: the engine is 1.27; Equalix is a participant in it.

## First customer — #250

`#250` (durable ingestion processing) is the first consumer of this contract.
Its acceptance criteria map onto the frozen shapes:

| #250 criterion | Contract shape |
|---|---|
| Workflow state + Operation status survive restart | §41, §97 durable state; `operation_id` link (§18) |
| Duplicate delivery doesn't duplicate effects | §24–§25 idempotency; `processed_events` |
| Retryable vs terminal distinguished | §76 `RETRYABLE`/`FAILED` |
| Failed step resumes without rerunning committed effects | §44 step + compensation; §24 idempotency |
| Correlation/causation connect source→operation→workflow→artifact→run | §13, §41 |
| Replay can't silently overwrite source history | §33 dead-letter identity retained; §6.3 immutability |

## Freeze scope (Phase 1)

Design 1.27 §104 Phase 1 ("Event Contract") is the freeze target: envelope,
event IDs, command IDs, correlation, causation, schema versioning, tenant scope
— plus the workflow shapes (§41–§45) the first slice needs. Phases 2–6 (outbox,
inbox, workflow execution, integration, hardening) are implementation, gated on
this contract.

## Cross-references

- `docs/architecture/synanton-design-1.27.md` — the authoritative design
- `docs/implementation/eventing-workflow-plane/INDEX.md` — implementation plan
- `docs/api/operation-model.md` — the public Operation the internal command links to
- `docs/api/identity-context.md` — the tenant/principal source for event scope
- [ADR-004](../../architecture/decisions/adr-004-eventing-workflow-plane.md) — the plane decision
