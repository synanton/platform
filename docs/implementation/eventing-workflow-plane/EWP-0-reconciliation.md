# EWP-0 — Design 1.27 contract ↔ implementation reconciliation

**Status:** Draft for review · **Date:** 2026-10-22 · **Tracker:** [platform #375](https://github.com/synanton/platform/issues/375)
**Frozen contract:** [`docs/api/eventing-contract.md`](../../api/eventing-contract.md), frozen 2026-10-17 by [#247](https://github.com/synanton/platform/issues/247)
**Scope:** Current event/outbox/workflow code in `java/ingestion-cache`, `java/synflux`, `java/topology`, and `java/syntology`; schema migrations V11–V15; EWP implementation plan.

This is an audit and scope decision, not implementation or a redesign of frozen Design 1.27. It distinguishes the already-shipped Synflux ingestion workflow/outbox path from the not-yet-implemented common Eventing and Workflow Plane.

**Section scope:** The matrix covers the frozen projection's load-bearing sections
that map to current publisher, workflow, failure, persistence, and sequencing
paths, plus the ten EWP-0 questions. It does not claim code validation of all 107
sections in the full 1.27 design; unexercised downstream capabilities are
classified as deferred to their EWP phase or owning plane.

## Method and classification

For each load-bearing contract section, compare frozen text to current source and schema. Evidence citations name paths and line ranges; migrations are identified by version and table/column. Use these classes:

- **MATCH** — observed behavior satisfies the contract for the stated scope.
- **INTERPRETATION** — a permitted implementation choice or a scoped reading; recorded so it is not re-litigated.
- **CONTRACT-CORRECTION** — frozen contract text contradicts required behavior and needs a dated amendment.
- **IMPLEMENTATION-GAP** — contract is clear but the generic plane or consumer behavior is not implemented.
- **DEFERRED** — intentionally belongs to a later EWP phase or dependent plane; a trigger is named.

The frozen projection is the citation surface for envelopes, outbox/inbox, retry, dead-letter, replay, and workflow records. Design 1.27 remains authoritative; this document does not alter it.

## Current implementation baseline

| Area | Observed state | Evidence |
|---|---|---|
| Source workflow | Synflux has one durable `IngestionWorkflow:v1` with `DISCOVER`, `ACQUIRE`, and `INGEST` steps. Workflow state, steps, and leases are stored in ingestion-cache Cassandra tables; restart, lease takeover, and committed-step recovery have tests. | `java/synflux/.../lifecycle/FixtureSourceWorkflow.java:51–60, 324–349, 631–710, 734–769`; V11 `workflow_states`, `workflow_steps`, `workflow_leases`; `FixtureIngestionLifecycleTest.java:1038–1100, 1148–1190, 1243–1289` |
| Tenant discovery | Current OutboxPublisher enumerates registered tenants from bounded registry partitions; an older EWP-plan statement about a hard-coded `demo`/`demo2` allowlist is stale. | `IngestionCacheClient.java:34–58`; V11 `tenant_registry`; `OutboxPublisherTest.java:35–51` exercises a non-demo tenant. |
| Source events | Synflux emits `SourcePublished` and `SourceDeleted`; `OutboxPublisher` sends them to Kafka topic `source_events`. Event intent is co-located with the source version or tombstone. | `FixtureIngestionService.java:631–724`; `IngestionCacheClient.java:708–730, 775–798`; `OutboxPublisher.java:99–117`; V9 source tables and V11 retry fields |
| Existing publisher mechanisms | The manifest-transition publisher is real but uses its legacy shape; topology logs events and marks them dispatched without publishing; syntology's file logger appends text and logs, without durable delivery acknowledgement. | V2.1 manifest outbox; `OutboxPublisher.java:71–97`; `java/topology/.../TopologyOutboxDispatcher.java:20–40`; `java/syntology/.../EventPublisher.java:3–6`, `FileEventLogger.java:15–40` |
| Generic Eventing implementation | `java/eventing-contract` and `java/workflow-engine` do not exist yet. No Java/CQL `processed_events`, `processed_source_versions`, `processed_source_deletions`, or `inbox_events` implementation, and no consumer of `source_events` was found. | EWP plan `INDEX.md` EWP-1–EWP-5 phases; repository Java/CQL search (2026-10-22) |
| V11–V15 schema changes | V11 adds Synflux workflow state/steps/leases, dead letters, and retry metadata. V12–V14 add processing-run and chunk/source-version provenance. V15 adds open-typed Operation details. V13/V14 `chunk_source_versions` is a provenance junction, not event-consumer deduplication. | `java/ingestion-cache/src/main/resources/cql/V11__durable_ingestion_workflows.cql` through `V15__operation_details.cql` |

## Contract-section reconciliation

| Delta ID | Contract section | Requirement | Code/schema evidence and finding | Classification / follow-on |
|---|---|---|---|---|
| D-11 | §9 Event identity | Stable, globally unique `event_id` across delivery retries. | Source event IDs are deterministic UUIDv5 values derived from the source-version or tombstone identity; stored IDs survive retry and DLQ requeue (`FixtureIngestionService.java:638, 672–675`; `IngestionCacheClient.java:856–883`). No generic typed event/command identifier exists. | **IMPLEMENTATION-GAP**, EWP-1 typed ID contract. |
| D-09 | §§13–15 Envelope, tenant, security | Envelope includes event identity/type/version/time, producer, tenant, correlation/causation, security claim, payload. | `eventEnvelope` emits the fields, with both nested `tenant.id` and top-level `tenant_id` (`FixtureIngestionService.java:703–723`). `security.classification` is fixed to `UNCLASSIFIED` and `policy_version` to `1`, not derived from a trusted classification context. | **IMPLEMENTATION-GAP**, EWP-1 propagates and validates trusted security metadata and the frozen envelope fields. |
| D-11 | §§17–18 Command identity/result | Stable command identity; accepted asynchronous work links to an Operation. | Step command IDs are deterministic from `(workflow_id, step_id)` and persisted in `workflow_steps` (`FixtureSourceWorkflow.java:631–646, 826–829`; V11). Synflux links to the existing Operation API, but no generic `Command` record/module exists. | **IMPLEMENTATION-GAP**, EWP-1 typed command contract. |
| D-07 | §22 Ordering | Entity ordering may be required; global ordering is not required. | Source rows are sorted per resource and Kafka keys include tenant + connector + source + resource (`IngestionCacheClient.java:732–798`; `OutboxPublisher.java:99–117`). The selector blocks followers when an earlier row is already delayed/dead-lettered at query time, but returns multiple ready rows together; the publisher continues after a send failure, so a later same-resource event in that batch can publish before the failed predecessor's retry. `OutboxPublisherTest` covers only a single-event failure, not this batch case (`java/ingestion-cache/src/test/java/org/synanton/ingestioncache/outbox/OutboxPublisherTest.java:54–78`). Manifest transitions are tenant-keyed and make no cross-manifest order claim. | **IMPLEMENTATION-GAP**, EWP-2: preserve per-entity order across an in-batch publication failure and test the predecessor/follower case. No global order is required by §22. |
| — | §23 Delivery | At-least-once by default; correctness does not depend on exactly-once broker delivery. | Publisher awaits Kafka acknowledgement and only then marks the row published (`OutboxPublisher.java:99–117`). Crash after broker ack but before the mark can redeliver the same event. | **MATCH** for publication; consumer idempotency remains EWP-3. |
| D-03 | §§24–25 Consumer idempotency | Persist `processed_events`; source consumers may also require business-key records; coordinate effect and dedup record atomically where required. | No event consumer or generic/source-key processed table exists in Java/CQL. Source-version `IF NOT EXISTS` deduplicates the producer's source fact (`IngestionCacheClient.java:708–730`; V9), not downstream consumer effects. | **IMPLEMENTATION-GAP**, EWP-3. Source-version dedup is not a substitute for consumer event dedup. |
| D-04, D-12 | §§26–30 Transactional publication, outbox, inbox | Persist local event intent with state; publish after commit; inbox may durably deduplicate intake. | `FixtureSourceWorkflow` acquires the artifact before invoking ingestion (`FixtureSourceWorkflow.java:443–473`); `AcquireStage` writes and round-trip verifies Content Cache bytes (`AcquireStage.java:39–61`). `FixtureIngestionService` then inserts SourceVersion plus event intent in one conditional Cassandra row (`FixtureIngestionService.java:164–189`; `IngestionCacheClient.java:708–730`; V9/V11). Manifest transitions use V2.1's legacy table. No generic `outbox_events` or `inbox_events` exists. | **IMPLEMENTATION-GAP**: D-04 consumer effect/dedup atomicity is EWP-3; D-12 common outbox/inbox contract is EWP-2/EWP-3. No cross-store transaction is claimed. |
| D-06 | §§31–32 Retry/backoff/classification | Bounded exponential retry using the contract categories `TRANSIENT`, `PERMANENT`, `SECURITY`, `INVALID`, `DEPENDENCY`, `TIMEOUT`, `RESOURCE`. | Outbox uses a boolean exception predicate and max five attempts; Synflux retries selected Java exception classes up to three times. A retry-policy JSON is stored per step but no shared contract-category mapping is used (`OutboxPublisher.java:34–38, 120–139`; `FixtureSourceWorkflow.java:59, 610–623, 772–785`). | **IMPLEMENTATION-GAP**, EWP-2/EWP-4. |
| D-13 | §§33–34 Dead-letter operations | Preserve original identity; operators can inspect, classify, replay, discard, or quarantine; replay adds context. | V11's `dead_letter_events` retains ID/payload/attempt/error. Source replay resets retry metadata and requeues the same ID/payload, then deletes the DLQ row (`IngestionCacheClient.java:820–883`; `FixtureIngestionLifecycleTest.java:1292–1338`). No generic operator disposition API or replay audit/context was found. | **IMPLEMENTATION-GAP**, EWP-3/EWP-6. |
| D-05 | §§38–39 Replay policy | Replay only durable facts; each event type declares `REPLAY_SAFE`, `REPLAY_WITH_POLICY`, or `NOT_REPLAYABLE`; replay is not command re-execution. | No event-type replay class/schema or `REPLAY_*` implementation was found. The source outbox replay method is DLQ redelivery with the original ID, not historical replay. | **IMPLEMENTATION-GAP**, EWP-1 event-type declaration and EWP-3/EWP-6 replay behavior. |
| D-14 | §§40–45 Workflow | Durable workflow identity/version/state, explicit steps, timeout, retry, compensation, and state machine. | V11 and `WorkflowStateRow`/`WorkflowStepRow` persist state, version, step, retry metadata, timeout value, and lease. Synflux implements one fixed in-process `IngestionWorkflow:v1` (`FixtureSourceWorkflow.java:51–60, 631–710, 734–769`). `expected_events_json` is `[]`; `timeout_millis` is stored but no timeout enforcement was found; no generic workflow engine exists. | **IMPLEMENTATION-GAP**, EWP-4. |
| D-02 | §§47/53 Equalix | Workflow Plane owns durable orchestration; Equalix participates without duplicating its domain logic. | `EqualixScheduler` and `InvalidatingRecalculationExecutor` exist in annotations, independently of a shared workflow engine. EWP plan assigns durable Equalix integration to EWP-5 (`INDEX.md:314–331`). | **DEFERRED**, EWP-5, as the plan already specifies. |
| D-01 | §48 Compensation | Four classes: `REVERSIBLE`, `COMPENSATABLE`, `INVALIDATABLE`, `IRREVERSIBLE`. | Synflux assigns three classes to DISCOVER/ACQUIRE/INGEST (`FixtureSourceWorkflow.java:686–692`). AAP-2 invalidates stale annotation rows (`InvalidatingRecalculationExecutor.java:8–34`), but does not encode `INVALIDATABLE` as a workflow-step class. | **IMPLEMENTATION-GAP**, EWP-4 generic step semantics. Existing AAP-2 invalidation is separate behavior, not evidence of an EWP compensation label. |
| D-06, D-14 | §§73–76 Failure/recovery | Failure model plus workflow states `RETRYABLE`, `FAILED`, `CANCELLED`, `WAITING`, `ESCALATED`. | Synflux emits `RETRYABLE`, `FAILED`, `CANCELLED`, `COMPLETED`, `RECEIVED`, and `RUNNING`; `ESCALATED` appears only in a terminal predicate and is never assigned; `WAITING` is absent (`FixtureSourceWorkflow.java:610–623, 695–725`). | **IMPLEMENTATION-GAP**, D-06 EWP-2 retry mapping and D-14 EWP-4 workflow states; operation mapping is separately defined in the frozen contract. |
| D-08 | §§97–100 Persistence/broker choice | State survives restart; PostgreSQL is recommended, not required; broker adoption is measurement-driven. | Current Synflux workflow state is in Cassandra (V11). EWP-4's plan chooses PostgreSQL-only for the future generic workflow engine (`INDEX.md:278–298`). | **INTERPRETATION.** Cassandra is allowed by §97; PostgreSQL-only is a future implementation-plan choice, not a contract requirement. |
| — | §104 Implementation phases | Contract, publication, consumption, workflow, integration, hardening. | EWP plan has EWP-0 preflight plus EWP-1–EWP-6; #247 froze the design contract, not EWP-1 code. | **MATCH** in sequence. The EWP-0 audit and EWP-1 implementation are separate gates. |

## Ten flagged questions — dispositions

| Delta ID | Question | Finding | Classification / follow-on |
|---|---|---|---|
| D-01 | INVALIDATABLE compensation | The class is absent from Synflux's three step labels. AAP-2 invalidation exists but is not workflow-step compensation. | **IMPLEMENTATION-GAP**, EWP-4. |
| D-02 | Equalix participation | The source runner is Synflux-local; the existing annotation Equalix scheduler is not attached to a common workflow engine. | **DEFERRED**, EWP-5. |
| D-03 | `processed_events` vs `processed_source_versions` | Neither table exists in code/schema. Source-version identity prevents duplicate producer facts, not duplicate consumer effects. | **IMPLEMENTATION-GAP**, EWP-3. |
| D-04 | Consumer atomicity | There is no source-event consumer to verify yet. Producer SourceVersion + event intent is one row; Content Cache publication precedes that row. | **IMPLEMENTATION-GAP**, EWP-3; producer publication order is verified by D-10. |
| D-05 | SourcePublished replay classification | No replay class is emitted/registered. Same-ID DLQ requeue is delivery redelivery, not classified historical replay. | **IMPLEMENTATION-GAP**, EWP-1/EWP-3/EWP-6. |
| D-06 | Failure taxonomy coverage | Exception booleans and partial workflow states do not implement the full seven retry classes and five workflow failure states. | **IMPLEMENTATION-GAP**, EWP-2/EWP-4. |
| D-07 | Entity vs global ordering | Global ordering is not promised, as §22 allows. Source events are sorted/keyed per resource, but a failure of an earlier event does not stop later events already selected in the same poll batch. The existing test exercises a single event only. | **IMPLEMENTATION-GAP**, EWP-2 must hold same-entity followers until the predecessor is acknowledged or explicitly dispositioned and add a multi-event failure test. |
| D-08 | Cassandra vs PostgreSQL | §97 recommends PostgreSQL but does not require it; Cassandra is allowed for the current slice. The future engine plan independently chooses PG-only. | **INTERPRETATION**, no contract correction. |
| D-09 | Envelope fields | All fields are present; security metadata is hard-coded rather than derived from trusted classification context. | **IMPLEMENTATION-GAP**, security-value propagation and validation in EWP-1. |
| D-10 | Content Cache → source commit → dispatch | Code verifies Content Cache bytes before conditionally committing SourceVersion + outbox intent; publisher dispatches after persisted intent and marks only after broker ack. | **MATCH**; no distributed transaction is claimed. PR #279 also records this ordering; code checked here is current. |

Additional gaps are tracked as D-11 (generic typed Event/Command identifiers), D-12 (common outbox/inbox contract), D-13 (operator dead-letter disposition/audit), and D-14 (generic workflow foundation). Every D-ID in the matrix has one permitted classification and a phase assignment or deferral.

## Additional plan-currentness correction

The EWP plan previously described `ingestion-cache.OutboxPublisher` as polling
a hard-coded `demo`/`demo2` allowlist. The stale claim in `INDEX.md` has been
corrected at its source. Current code registers tenants in V11's
`tenant_registry`; `IngestionCacheClient.listTenantIds()` enumerates its
bounded partitions and the publisher loops over those IDs. `OutboxPublisherTest`
also exercises a non-demo tenant. The implementation plan has been corrected:
dynamic discovery is existing behavior; EWP-2 is about bringing the legacy
manifest/source outbox records and publishing contract into conformance, not
re-implementing tenant enumeration.

## EWP-1 scope derived from the audit

The contract is frozen; EWP-1 is implementation, not another design/freeze cycle. EWP-1 scope is derived only from implementation-gap deltas assigned to EWP-1, not from all gaps in the audit. After EWP-0 closes, EWP-1 should cover:

1. **D-11:** add `java/eventing-contract` with typed Event and Command records and stable typed identifiers matching frozen §§9, 13, and 16–18.
2. **D-09:** round-trip the frozen envelope fields, propagate and validate trusted tenant/security metadata, and fail closed on missing or invalid context.
3. **D-05 (EWP-1 portion):** include the frozen event-type replay classification in the typed contract; replay execution/disposition remains EWP-3/EWP-6.
4. Define and test correlation/causation propagation and schema-version semantics as part of those EWP-1 gaps.
5. Do not pull outbox publication, consumer/inbox dedup, workflow execution, or production hardening into EWP-1; those remain EWP-2 through EWP-6.

**EWP-2 acceptance criterion (D-07):** the ordering test must select at least
two events for one aggregate, fail publication of the first, and prove the
second is withheld until the first is acknowledged or explicitly dispositioned.
The current selector's next-poll blocking does not cover failures that occur
after multiple rows have already been returned in one batch.

## EWP-0 decision

No frozen-contract amendment is indicated by this audit. Observed differences classify as implementation gaps, permitted persistence choices, or plan-defined deferrals. The three publisher fates are:

- **Topology:** replace the logging-only Kafka stub with the common EWP-2 publisher while preserving the durable topology outbox.
- **Ingestion Cache:** adapt the legacy manifest transition and source lifecycle publishers to the frozen publisher/envelope contract; retain SourceVersion/tombstone co-location of source publication intent for local atomicity, and preserve per-entity order when a publish in a selected batch fails.
- **Syntology:** replace `FileEventLogger` as a delivery mechanism with durable EWP publication; file logging may remain diagnostic only.

EWP-0 closes when all of these are recorded and accepted in [#375](https://github.com/synanton/platform/issues/375): (1) every in-scope matrix row has evidence and exactly one permitted classification; (2) every D-ID has a phase assignment or a deferred owner/trigger; (3) any contract correction has an ADR amendment draft or explicit deferral (this audit finds none); (4) the EWP-1 scope above is accepted and derives only from EWP-1 implementation-gap deltas; (5) the D-07 EWP-2 ordering acceptance criterion is recorded; and (6) stale plan claims, including tenant discovery, are corrected at their source. Until issue #375 closes, EWP-1 implementation tickets remain gated.
