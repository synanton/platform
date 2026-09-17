---
title: "Eventing and Workflow Plane - Implementation Plan"
status: "not started"
last_reviewed: "2026-09-15"
---

# Eventing and Workflow Plane - Implementation Plan

**Purpose:** Implementation plan for Design 1.27 - the common asynchronous execution fabric (events, commands, workflows, retries, recovery) that Designs 1.26 and 1.28-1.31 depend on rather than each inventing their own.
**Architecture reference:** `docs/architecture/synanton-design-1.27.md`; [ADR-004](../../architecture/decisions/adr-004-eventing-workflow-plane.md); capstone positioning in `docs/architecture/synanton-platform-architecture-1.0.md` §6, §14, §15.
**Target repository:** `synanton/platform` (this repo) - no cross-repo split; unlike the extraction plane, there is no separate deployable service implied by the design.
**Audience:** Architects, module owners, platform engineers
**Last Updated:** 2026-09-15

---

## Theme

> Events are immutable facts. Commands express intent. Workflows are durable state machines. At-least-once delivery plus idempotent consumption gives effectively-once business behavior - physical exactly-once delivery is never required.

---

## User-Facing Capability Unlocked

- Ingestion, annotation recalculation, AI execution and search-projection updates stop being invented independently by each plane and instead run over one audited async substrate.
- A crashed or restarted service resumes in-flight work correctly: no operation is silently lost, no event is silently dropped, no workflow is stuck without a recovery path.
- Cross-plane provenance (`correlation_id`/`causation_id`) makes it possible to answer "what triggered this?" across service boundaries, not just within one service's logs.
- The recalculation path (Resolutor → Equalix, Design 1.25) gets a durable, retryable execution mechanism instead of best-effort in-process calls.

---

## Non-Negotiable Invariants

Derived from the capstone doc's Communication invariants (17-23) and Design 1.27 itself:

1. **Events are immutable facts.** Once published, an event's content never changes; a correction is a new event.
2. **Commands express intent, not completion.** A command being accepted does not mean the requested work has happened.
3. **At-least-once delivery is the default and only mandated model.** No component may assume exactly-once physical delivery.
4. **Consumers are idempotent.** Every consumer MUST tolerate redelivery of the same event/command without duplicating effects.
5. **Large payloads are referenced, not inlined,** in event/command envelopes.
6. **Workflow state is durable.** A workflow's progress survives process restarts; recovery is a first-class path, not an afterthought.
7. **Replay is explicitly classified.** Whether and how an event stream can be replayed is a declared property, not an accident of implementation.
8. **No mandated broker or workflow engine.** Kafka, NATS, RabbitMQ, Pulsar, Redis Streams and "Postgres-as-broker" are all explicitly non-mandated (§5); the design's own §99 recommendation is to start **PostgreSQL-only** and add a broker only once §100's measured criteria (sustained high throughput, large fan-out, long retention/replay, cross-service isolation, independent scaling) actually apply.
9. **This plane does not duplicate Design 1.25's domain logic.** Resolutor (impact analysis) and Equalix (recalculation execution) remain owned by 1.25; this plane only gives them a durable, retryable substrate to run on.

---

## Known Debt This Plan Must Absorb

Three ad-hoc, non-conformant event/outbox mechanisms already exist in `platform`. EWP-0 exists specifically so Phase 1's envelope design accounts for all three instead of becoming a fourth shape.

| Module | Current state | File |
|---|---|---|
| `topology` | Outbox dispatcher polls a table but its Kafka publish is a dead `// TODO Phase 4` stub - rows are marked `dispatched=TRUE` after logging, nothing is ever actually published. | `java/topology/.../infra/outbox/TopologyOutboxDispatcher.java` |
| `ingestion-cache` | Real `KafkaProducer`, polls `manifest_transitions_outbox` every 2s, genuinely at-least-once - but hardcoded to `List.of("demo", "demo2")` as the only tenants it will ever poll. No correlation/causation IDs, no schema version, no consumer-side inbox/dedup found. | `java/ingestion-cache/.../outbox/OutboxPublisher.java` |
| `syntology` | `EventPublisher` port is `void publish(String eventType, String payload)` - no envelope at all - backed by `FileEventLogger`, essentially a stub. | `java/syntology/.../domain/port/out/EventPublisher.java` + `FileEventLogger` |

---

## Phased Delivery

Phases follow Design 1.27 §104 directly (Event Contract → Reliable Publication → Reliable Consumption → Workflow Foundation → Platform Integration → Production Hardening), with a pre-flight phase (EWP-0) added by this plan to absorb the debt above before it hardens further.

| Phase | Name | Status |
|-------|------|--------|
| EWP-0 | Pre-flight: inventory and reconciliation decision | Not started |
| EWP-1 | Event Contract | Not started |
| EWP-2 | Reliable Publication | Not started |
| EWP-3 | Reliable Consumption | Not started |
| EWP-4 | Workflow Foundation | Not started |
| EWP-5 | Platform Integration | Not started |
| EWP-6 | Production Hardening | Not started |

---

## Changes in `synanton/platform`

### New modules

| Module | Type | Purpose |
|---|---|---|
| `java/eventing-contract` | library | Event/Command envelope types, outbox/inbox/workflow-state record shapes, `EventPublisher`/`EventConsumer`/`EventSubscription`/`EventStore` interfaces (§98). Mirrors how `java/extraction-contract` carries a contract with no service dependencies. |
| `java/workflow-engine` | library/service | Workflow definition, durable state, timeout, cancellation, recovery (EWP-4). PostgreSQL-backed only, per §99 - no broker dependency. |

### Modified modules

| Module | Change |
|---|---|
| `java/topology` | `TopologyOutboxDispatcher`'s dead Kafka stub is replaced with a real `eventing-contract`-conformant publisher (EWP-2). |
| `java/ingestion-cache` | `OutboxPublisher` is generalized off its hardcoded `["demo","demo2"]` tenant list and upgraded to the new envelope/outbox schema (EWP-2). |
| `java/syntology` | `EventPublisher`/`FileEventLogger` replaced with the conformant publisher once the envelope contract lands (EWP-1/EWP-2). |
| `java/annotations` | Resolutor/Equalix (AAP-2, already landed) get a durable, retryable execution path via the new workflow engine instead of in-process invocation (EWP-5). |
| `settings.gradle.kts` | Include the two new modules. |

### Deliberately unchanged

- No new deployable service and no separate repository - this plane is a library + shared Postgres schema consumed in-process by existing services, per §99's PostgreSQL-only recommendation.
- `java/extraction-contract` / `content_extractor` - the extraction plane's own async model (`SubmitExtraction`/`GetOperations`) is a separate, already-shipped concern; EWP-5 records where it should eventually align (see PAPI-3's `ExtractionStatus` reconciliation task) but does not rewrite it as part of this plan.

---

## The Contract - Core Shapes

Full definitions live in `docs/architecture/synanton-design-1.27.md`; the load-bearing shapes are reproduced here so a ticket can reference a field list without opening the design doc.

**Event envelope (§13):**
```yaml
event_id: ...
event_type: ...
event_version: ...
occurred_at: ...
producer: { service: ..., version: ... }
tenant: { id: ... }
correlation_id: ...
causation_id: ...
security: { classification: ..., policy_version: ... }
payload: {}
```

**Command (§16):**
```yaml
command_id: cmd-123
command_type: GenerateEmbedding
command_version: 1
tenant_id: tenant-a
target: { document_id: doc-42 }
model: { id: embedding, version: "4" }
```

**Outbox record (§28):** `event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id, payload, created_at, published_at, attempt_count, last_error`

**Inbox record (§30):** `consumer_id, event_id, received_at, processed_at, status`

**Workflow state (§41):** `workflow_id, workflow_type, workflow_version, tenant_id, state, created_at, updated_at, started_at, completed_at, current_step, correlation_id, failure_code, failure_message`

**Retry error classes (§32):** `TRANSIENT, PERMANENT, SECURITY, INVALID, DEPENDENCY, TIMEOUT, RESOURCE` - only appropriate categories are retried.

**Compensation classes (§48):** `REVERSIBLE, COMPENSATABLE, INVALIDATABLE, IRREVERSIBLE`.

**Consistency classes (§69):** `STRONG, BOUNDED_EVENTUAL, EVENTUAL, BEST_EFFORT` - each operation should declare which it offers.

---

## Phase EWP-0 - Pre-flight

**Goal:** decide how the three existing ad-hoc mechanisms fold into EWP-1's envelope before that envelope is designed, so it isn't retrofitted a fourth time.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Written inventory of `TopologyOutboxDispatcher`, `ingestion-cache`'s `OutboxPublisher`, and `syntology`'s `EventPublisher`/`FileEventLogger` - current schema, current gaps, call sites. |
| 2 | Decision record: migrate each in place onto the new envelope/outbox schema (EWP-2) vs. replace outright - written down, not left implicit. |
| 3 | List every current caller of `ingestion-cache.OutboxPublisher` and `syntology.EventPublisher` that must be touched when the schema changes. |

### Definition of Done

1. A short decision doc (or ADR addendum) names the fate of each of the three mechanisms before EWP-1's envelope PR is opened.
2. No new ad-hoc event/outbox mechanism is introduced elsewhere in the codebase between now and EWP-2 landing (grep check in CI or PR review).

---

## Phase EWP-1 - Event Contract

**Goal:** one envelope shape, one ID/versioning scheme, enforced tenant scope - the hard serialization point every later phase and every other plane builds against.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `java/eventing-contract` module: `Event`, `Command` types matching the §13/§16 shapes above. |
| 2 | Event ID and Command ID format (collision-resistant, sortable or not - decide and document). |
| 3 | Correlation ID / causation ID propagation rules across service boundaries (how a consumer sets its own `causation_id` from the triggering event's `event_id`). |
| 4 | Event/command schema versioning policy (`event_version`/`command_version` semantics: additive-only within a version, explicit bump on breaking change). |
| 5 | Tenant-scope validation rule: every envelope carries `tenant.id`; consumers MUST reject or fail-closed on a missing/invalid tenant. |
| 6 | Security context propagation: `security.classification` and `policy_version` survive serialization across the async boundary (ties to capstone Security invariant 5). |

### Definition of Done

1. `Event` and `Command` records round-trip through JSON (or the chosen serialization) with every field in §13/§16 preserved.
2. A missing `tenant.id` fails closed at the contract layer, not downstream in each consumer independently.
3. Two events with the same `causation_id` chain can be traced back to a single root `event_id` in a test fixture.
4. `./gradlew build` green with the new module included in `settings.gradle.kts`.

---

## Phase EWP-2 - Reliable Publication

**Goal:** outbox pattern, retryable and monitorable, replacing all three ad-hoc mechanisms identified in EWP-0 - not adding a fourth.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Outbox table/record per the §28 shape above, shared schema (or shared library over per-service tables - decide once, document, apply everywhere). |
| 2 | `EventPublisher` implementation: claims outbox rows, publishes, marks `published_at`, increments `attempt_count`/`last_error` on failure. |
| 3 | Migrate `ingestion-cache.OutboxPublisher` off `List.of("demo","demo2")` onto real dynamic tenant enumeration and the new schema. |
| 4 | Replace `TopologyOutboxDispatcher`'s dead Kafka stub with the real publisher. |
| 5 | Replace `syntology`'s `EventPublisher`/`FileEventLogger` with the conformant publisher. |
| 6 | Publication retry policy using the §32 error classes; publication-lag monitoring metric. |

### Definition of Done

1. An event written to the outbox by any of the three migrated modules is published exactly the same way (same code path, same envelope shape) - no per-module publish logic remains.
2. Killing the publisher process mid-batch and restarting it results in every unpublished row eventually published, none duplicated beyond what at-least-once already allows.
3. `ingestion-cache` publishes events for tenants other than `demo`/`demo2` in a test.
4. A publication failure increments `attempt_count` and records `last_error`; it does not silently mark the row published (regression test against `TopologyOutboxDispatcher`'s prior behavior).

---

## Phase EWP-3 - Reliable Consumption

**Goal:** idempotent consumption, dead-letter handling - the other half of at-least-once delivery.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Inbox table/record per the §30 shape above. |
| 2 | Deduplication: a consumer checks the inbox before processing and skips already-`processed` `event_id`s for its `consumer_id`. |
| 3 | Acknowledgement + retry policy using the §32 error classes (only `TRANSIENT`/`TIMEOUT`/`RESOURCE`-class failures retry automatically). |
| 4 | Dead-letter path for events that exhaust retries, with enough context (`event_id`, `consumer_id`, `last_error`) to diagnose without replaying blindly. |

### Definition of Done

1. The same event delivered twice to the same consumer is processed exactly once (verified by a redelivery test).
2. A `PERMANENT`-class failure does not retry; a `TRANSIENT`-class failure does, up to a bounded count, then dead-letters.
3. A dead-lettered event is inspectable (query by `consumer_id`) without needing broker-level tooling.

---

## ◆ Checkpoint - Vertical Slice 1 (§105)

Run before starting EWP-4:

```text
ContentPublished → Outbox → Event Publisher → Consumer → GenerateEmbedding Command
  → AI Runtime → ExecutionCompleted → Workflow → EmbeddingPublished
```

This validates the architecture end to end using only EWP-1 through EWP-3 (the workflow step can be a minimal placeholder until EWP-4 lands, per the design's own sequencing).

---

## Phase EWP-4 - Workflow Foundation

**Goal:** durable, recoverable multi-step coordination - the primitive Design 1.25's Equalix and the recalculation path need.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `java/workflow-engine` module: workflow definition format, workflow state record per the §41 shape above. |
| 2 | Durable execution: each step's completion is persisted before the next step is attempted. |
| 3 | Timeout handling per workflow/step. |
| 4 | Cancellation: a workflow in a non-terminal state can be cancelled; cancellation is itself durable. |
| 5 | Recovery: a killed-and-restarted engine resumes every non-terminal workflow from its last persisted step, with no work silently lost and no workflow stuck without a lease. |
| 6 | PostgreSQL-only implementation per §99 - explicitly no broker dependency at this phase. |

### Definition of Done

1. A workflow started, then the process killed mid-step, resumes correctly after restart - no duplicate side effects beyond what at-least-once already allows, no stuck state.
2. A cancelled workflow does not resume after restart.
3. A workflow that times out on a step reaches a terminal failure state, not an indefinite `RUNNING`.
4. No Kafka/Redis/broker dependency appears in `java/workflow-engine`'s build file.

---

## ◆ Checkpoint - Vertical Slice 3, Failure Recovery (§107)

Run before starting EWP-5:

```text
command → workflow → runtime → injected failure → retry → runtime → completed
```

Then restart the process at every stage of this flow and confirm recovery holds at each restart point - this is the test the design doc calls out explicitly, not an optional extra.

---

## Phase EWP-5 - Platform Integration

**Goal:** wire the substrate built in EWP-1 through EWP-4 into the planes that actually need it.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Content Cache (1.26) lifecycle events - once 1.26 exists; until then, this deliverable is blocked, not skipped (see capstone §14 step 3 ordering). |
| 2 | Processing Runs / Equalix (1.25) get a durable execution path via `java/workflow-engine`, replacing today's in-process Resolutor→Equalix call. |
| 3 | AI Runtime (1.30) execution events - once 1.30 exists. |
| 4 | Search projection update events (1.31) - once 1.31 exists. |
| 5 | Analytics as an asynchronous derived consumer of the event stream. |

### Definition of Done

1. Equalix-driven recalculation survives a process restart mid-recalculation (reuses EWP-4's recovery guarantee, applied to a real AAP-2 workflow).
2. Deliverables 1, 3, 4 are tracked as blocked-not-skipped against 1.26/1.30/1.31's own implementation status until those land.

---

## ◆ Checkpoint - Vertical Slice 2, Recalculation (§106)

The design doc calls this "the most important cross-plane use case":

```text
ModelVersionActivated → Resolutor → Recalculation Plan → Workflow → Equalix
  → AI Runtime → Knowledge Updated → Projection Updated
```

---

## Phase EWP-6 - Production Hardening

**Goal:** the plane is safe to depend on in production, not just correct in a demo.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Security review: classification/tenant context genuinely survives every async boundary (capstone Security invariant 5). |
| 2 | Per-tenant quotas and backpressure on publication/consumption. |
| 3 | Observability: publication lag, consumption lag, dead-letter rate, workflow-recovery rate as metrics. |
| 4 | Audit trail for replay operations (§7's replay-classification invariant made operational). |
| 5 | Replay controls: who can trigger a replay, over what event range, and how that's logged. |
| 6 | Load testing against the §100 criteria that would justify introducing a dedicated broker - confirms whether Postgres-only still holds or the criteria have been met. |

### Definition of Done

1. A tenant-scoped load test confirms per-tenant quotas actually bound one tenant's impact on another's consumption lag.
2. A replay is fully attributable in the audit trail (who, when, what range).
3. §100's broker-adoption criteria are evaluated against real measured load, not assumed.

---

## Sequencing and Parallelism

```text
EWP-0 (pre-flight decision)
   |
EWP-1 (event contract - hard serialization point)
   |
   +--------------------------+
   |                          |
EWP-2 (reliable publication)  |
   |                          |
EWP-3 (reliable consumption)  |
   |                          |
   +--------------------------+
                |
        ◆ Checkpoint: vertical slice 1
                |
        EWP-4 (workflow foundation)
                |
        ◆ Checkpoint: failure-recovery slice
                |
        EWP-5 (platform integration - partially blocked on 1.26/1.30/1.31)
                |
        ◆ Checkpoint: recalculation slice
                |
        EWP-6 (production hardening)
```

EWP-1 is the hard serialization point, matching the extraction plane's SCEP-1 precedent: after it, publication and consumption work can proceed in parallel against the same envelope contract.

---

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| EWP-0's reconciliation is skipped under schedule pressure | A fourth ad-hoc event mechanism appears, the exact failure the design's Motivation section predicts | Treat EWP-0's decision doc as a hard gate before EWP-1's envelope PR merges |
| Workflow engine reaches for a broker prematurely | Violates §99's explicit "prevents premature infrastructure expansion" guidance | EWP-4's DoD requires zero broker dependency; §100 criteria evaluated only in EWP-6 with real load data |
| Equalix migration to the workflow engine changes recalculation timing/ordering | Regression in AAP-2's already-shipped recalculation behavior | EWP-5 deliverable 2 is tested against AAP-2's existing recalculation test suite before and after migration |
| `ExtractionStatus` (already shipped) and this plane's eventual Operation model diverge silently | A second async status model with different terminal states, same failure mode the design warns about | Tracked jointly with the Platform API plan (PAPI-3) - reconciliation happens once, in one place |

---

## How to Contribute

1. A phase is not done until its numbered Definition of Done is fully satisfied - partial completion is reported as partial.
2. EWP-0's decision doc must exist and be linked from this file before EWP-1 work is ticketed.
3. Each Deliverable row above is sized to be one ticket; split further only if a reviewer asks.

---

## References

- `docs/architecture/synanton-design-1.27.md` - §13, §16, §28, §30, §32, §41, §48, §69, §98-100, §101-102, §104-107
- [ADR-004](../../architecture/decisions/adr-004-eventing-workflow-plane.md)
- `docs/architecture/synanton-platform-architecture-1.0.md` - §6, §14, §15
- `docs/implementation/content-extraction-plane/INDEX.md` - precedent for this document's phase/deliverable/DoD structure
