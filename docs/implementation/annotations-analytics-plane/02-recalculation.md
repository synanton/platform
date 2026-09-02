# AAP-2 - Recalculation (Resolutor + Equalix)

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 2](../../architecture/synanton-design-1.25.md), §48-§53, §62

**Enforces invariants:** 9 (recalculable), 11 (provenance preserved), 12 (background recalc cannot starve interactive workloads).

---

## Goal

Build **Resolutor** (deterministic impact analysis: given a change, which objects/dependencies/projections/analytics are affected) and **Equalix** (priority/concurrency-controlled execution of the resulting recalculation plan), so a definition, rule, model, dictionary, source or policy change triggers only the affected recalculation - never a full re-ingest, and never at the expense of interactive workloads.

## Work items

1. **Change-detection inputs** - wire the events Resolutor consumes (design §49):
   - Annotation definition publish (`annotations` service, AAP-1) - new Kafka topic `annotation_definition_published`.
   - Source/chunk change - reuse existing `ingestion-cache` outbox (`OutboxPublisher`).
   - Classification policy change - reuse the SEC-6 `ReindexAfterPolicyChangeWorkflow` trigger in `control-plane`.
   - Embedding model change - new config-driven trigger (manual admin action in this phase; automatic detection is out of scope).
2. **Resolutor** - `java/annotations/.../resolutor/`:
   - `ResolutorService.resolve(ChangeEvent) -> RecalculationPlan` - walks `dependency_edges` (AAP-1) to compute affected `{objects, dependencies, projections, analytics}` per design §49's output shape.
   - Deterministic for a given dependency graph and change set (design §49) - covered by a property-style unit test asserting repeat calls with the same graph/change produce identical plans.
   - `RecalculationPlan` record: ordered list of `{targetType, targetId, definitionId, fromVersion, toVersion}` work items plus an `affectedProjections` set (`INDEX`, `VECTOR`, `GRAPH`, `ANALYTICS`) derived from the design §51 Change Impact Model table.
3. **Equalix** - `java/annotations/.../equalix/`:
   - `EqualixScheduler` (package-local name - see [INDEX.md Open Questions](./INDEX.md#open-questions) on the GPU-track naming overlap) consumes `RecalculationPlan`s from a new Kafka topic `recalculation_requests`.
   - Workload classes per design §50: `INCREMENTAL_INGESTION`, `INTERACTIVE`, `USER_TRIGGERED_RECALC`, `HISTORICAL_RECALC`, `ANALYTICS_REBUILD`, `PROJECTION_REBUILD`.
   - Priority + concurrency controls: a bounded worker pool per workload class, with `INTERACTIVE`/`INCREMENTAL_INGESTION` given strictly higher scheduling priority than `HISTORICAL_RECALC`/`ANALYTICS_REBUILD` (design §62 - "background maintenance must not starve incremental and interactive workloads").
   - Each executed work item creates a new `processing_run` (AAP-1) of kind `evaluation_run` and re-invokes `AnnotationStage`'s producer for only the affected `(targetType, targetId, definitionId)` tuples - not a full re-ingest.
4. **Lifecycle handling** - historical annotation facts remain queryable across versions (design §52) unless governance requires invalidation; recalculation sets `invalidated_at` on the superseded annotation row rather than deleting it.
5. **Configuration** - `AnnotationsProperties.Resolutor`, `AnnotationsProperties.Equalix` nested records: worker-pool sizes per workload class, priority weights, retry/backoff policy.

## Definition of Done

1. Publishing a new annotation definition version produces a `RecalculationPlan` listing exactly the chunks/annotations that depend (transitively) on the changed definition - not the whole corpus.
2. Two Resolutor calls with an unchanged dependency graph and the same change event produce byte-identical `RecalculationPlan`s (determinism test).
3. Equalix executes `HISTORICAL_RECALC` work items at lower priority than concurrently queued `INTERACTIVE`/`INCREMENTAL_INGESTION` items under load - verified by a test that saturates the worker pool with both classes and asserts interactive-class latency stays within a fixed bound regardless of queued historical volume.
4. A definition version bump (`v3` → `v4`) creates new annotation rows tagged `v4` while `v3` rows remain queryable with `invalidated_at` set, not deleted (design §52, §75).
5. Each executed recalculation work item is backed by a traceable `processing_run` row (AAP-1) with `producer`/`producer_version`/`scope` populated.
6. Unit tests: Resolutor dependency-graph traversal (including diamond dependencies and the `A→B→C→A` cycle rejection carried from AAP-1), Equalix priority scheduling, evaluation-run provenance linkage.

## Key files

| File | Change |
|------|--------|
| `java/annotations/.../resolutor/ResolutorService.java` | New - impact analysis |
| `java/annotations/.../resolutor/RecalculationPlan.java` | New record |
| `java/annotations/.../equalix/EqualixScheduler.java` | New - controlled execution |
| `java/annotations/.../equalix/WorkloadClass.java` | New enum |
| `java/control-plane/.../workflow/ReindexAfterPolicyChangeWorkflow.java` | Wire as a Resolutor change-event source |
| `java/ingestion-cache/.../outbox/OutboxPublisher.java` | Emit change events Resolutor subscribes to |
| `java/annotations/src/main/resources/application.yml` | Worker-pool + priority config |

---

[← AAP-1 Annotation Foundation](./01-annotation-foundation.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-3 Knowledge Projections](./03-knowledge-projections.md)
