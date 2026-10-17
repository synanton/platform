# Eventing 1.27 Contract

**Status:** Draft — #247 artifact ("Freeze Eventing 1.27 delivery and workflow contract")
**Source:** Design 1.27 (§8–§27 event/command, §33–§34 dead letters, §41–§45 workflow, §73–§76 failure, §97 persistence, §104 phases)

The frozen contract for the platform's common asynchronous execution fabric.
This is a **projection** of Design 1.27, which is design-complete (2697 lines,
111 sections) — not a new design. It freezes the shapes every plane (1.26,
1.28–1.31) integrates with rather than re-deriving.

## Scope

#247's list, mapped to the design:

| Ticket item | Design source |
|---|---|
| event/command semantics | §8–§19 |
| ordering | §22 |
| idempotency | §24–§25 |
| dead letters | §33–§34 |
| durable workflow state | §41–§45, §97 |
| retries | §23, §75–§76 |
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
transaction where possible.

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

**Persistence (§97):** workflow state must be recoverable independently of the
process. PostgreSQL is a recommended initial implementation, not a Platform
requirement.

## Correlation and causation

Every event envelope carries `correlation_id` and `causation_id` (§13), and
workflow state carries `correlation_id` (§41). These connect source, operation,
workflow, artifact, and processing run across service boundaries — the
cross-plane "what triggered this?" property.

## Equalix's role

Equalix is a **workflow participant**, not the engine (§53). The Workflow Plane
is the fabric; Equalix executes as a participant coordinating recalculation.
The generic Workflow Plane must not duplicate Equalix's dependency-analysis
logic (§53). This resolves the capstone's "Workflow / Equalix" box: the engine
is 1.27; Equalix is a participant in it.

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
- [ADR-004](../architecture/decisions/adr-004-eventing-workflow-plane.md) — the plane decision
