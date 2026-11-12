# Phase 2 — Knowledge Maintenance: inspection and plan

**Status:** Proposed — 2026-11-12
**Epic:** #634 Knowledge Operations Foundation
**Scope:** Phase 2 only. This document is an inspection and execution plan, not authorization to implement #592 or #589 yet.
**Current tree:** `e78255a6` (Phase 1 closed; #618 superseded).

## Phase 2 structure

| Phase | Deliverable | Acceptance |
|---|---|---|
| **2a — Reconciliation completion** | Capability composition/startup validation, rule-5 boundary lint, #587 state resolution; #589 harness developed in parallel | #592 merged with green tests; #589 harness exists but final evidence deferred |
| **2b — Source-change lifecycle** | ADR + implementation: version fencing, assertion retirement, search-index replacement | Stale extractions cannot mutate the graph; old assertions retire without deleting provenance; changed source replaces indexed state; status/retry semantics are explicit |
| **2c — End-to-end maintenance** | Live maintenance E2E | Change a source document; stale assertions retired, new assertions promoted, retrieval returns current evidence with provenance |

Phase 2 is **not complete after 2a**. 2b is the critical path; 2c is the proof.

## 1. Inspection: seven questions

### Q1. What does #592 actually require to close?

#592 is the migration from the #585 transitional reconciliation seam to the #587 graph contracts, plus capability declaration/validation and the Neo4j-boundary lint. Its acceptance criteria are:

- all reconciliation graph access through #587 contracts;
- the transitional seam deleted;
- a rule-5 lint/test active and passing for Cypher/Neo4j types outside the adapter;
- workflow capability requirements explicit and startup-validated;
- Neo4j behavior and #585 recovery/idempotency/isolation/fence tests unchanged;
- applicable Neo4j/InMemory conformance green;
- #585/#587/deviation links.

Current state:

- **Mostly migrated.** `ResolutionReconciler` uses `ResolutionGraphPort`, not the old seam:
  `java/relix/src/main/java/org/synanton/relix/reconciliation/ResolutionReconciler.java:46-48,128-153,156-183`.
- `ResolutionGraphPort` exposes exact-identity reads and `ensureResolution`:
  `java/shared/common/src/main/java/org/synanton/common/resolution/ResolutionGraphPort.java:9-32`.
- The Neo4j adapter implements the observation and conditional write, including fencing:
  `java/relix/src/main/java/org/synanton/relix/adapter/out/graph/neo4j/Neo4jGraphConnector.java:297-369,522-582`.
- The transitional seam name is absent from the tree; the remaining reference is historical prose in `ResolutionReconciler.java:37-40`.
- **Capability validation is incomplete.** `ReconciliationConfig` checks only the boolean
  `supportsResolutionReconciliation()` (`java/relix/src/main/java/org/synanton/relix/reconciliation/ReconciliationConfig.java:22-33`), not the full `GraphCapabilities` composition now defined at
  `java/shared/common/src/main/java/org/synanton/common/resolution/GraphCapabilities.java:7-42`.
- **No rule-5 lint/test was found** for Cypher/Neo4j types outside the adapter. Cypher imports are still in the adapter and Neo4j metadata store, but there is no guard test.
- **InMemory does not implement resolution operations.** `InMemoryGraphConnector` has no `activeResolution`, `hasResolutionHistory`, or `ensureResolution`; it inherits unsupported/false from `ResolutionGraphPort`. This is honest for the current adapter but means “applicable conformance” is Neo4j-only today.
- #587 is still open, and #592 is still marked blocked by it. In practice the contract code needed by #592 is on `main`; the remaining #587 dependency is review/closure of the contract issue, not absent code.

**Conclusion:** #592 is close, not complete. Remaining work is capability-composition validation, the rule-5 boundary test, and confirming the #587 issue state. No schema/CQL migration is involved.

### Q2. What does #589 actually require to close?

#589 is the pre-activation combined fault-injection gate for `RESOLUTION_CONSUMER_ENABLED`, `PROMOTION_CONSUMER_ENABLED`, and `RECONCILIATION_ENABLED`. It requires a repeatable real-stack harness covering:

- concurrent consumer/reconciler instances and competing claims;
- lost response after a successful graph commit, for resolution **and promotion**;
- lease expiry/reclaim while another worker is healthy;
- process restart with in-flight `PROCESSING` work;
- Cassandra-updated/graph-missing repair;
- graph-present/Cassandra-incomplete repair with exact identity/fence;
- higher graph fence never overwritten;
- stale active edge with non-RESOLVED decision → metric + identifiers, no mutation;
- owner/lease preservation;
- extraction status untouched;
- independent enable/disable of each loop;
- real stores, no mocked graph writes for lost-response/concurrent repair;
- evidence linked, and a **post-#592 rerun** on the tree that will actually run.

Current evidence:

- Local real-store Testcontainers: `java/relix/src/test/java/org/synanton/relix/integration/ConsumerFaultInjectionIntegrationTest.java:113-330` covers lost response after resolution commit, transient failure, Cassandra completion from graph, missing target, expired-worker fence, restart, and one normal resolution→promotion path.
- Additive-load/restart: `docs/acceptance/k8s-activation-2026-11-11.md:96-125`; ADR-025 and regressions from #612.
- Live K8s activation and restart evidence: `docs/acceptance/k8s-activation-2026-11-11.md:9-141`.
- No full combined matrix: the same document says the full combined bundle and post-#592 rerun remain outstanding
  (`docs/acceptance/k8s-activation-2026-11-11.md:138-144`).

**Conclusion:** #589 is materially larger than #592 and cannot close from the current suite.

### Q3. Does #589 genuinely depend on #592?

**Partially overlapping, not strictly serial.**

- #592 changes the graph-access and capability boundary that #589 must exercise. Final #589 evidence must be collected on the post-#592 tree, so the last gate is serial.
- The #589 harness can be built in parallel against the current ports because the seam is already effectively gone. Its scenarios can be written and debugged now, but the pass artifact must be regenerated after #592 lands.
- Shared contracts: `ResolutionGraphPort`, `GraphCapabilities`, `ClaimService` work rows/leases, `ResolutionReconciler`, `PromotionConsumer`.
- Do not use pre-#592 results as final activation evidence; the existing issue comment already records this rule.

### Q4. Current state of reconciliation in the code

Implemented:

- scheduler/poll and batching: `ResolutionReconciler.java:76-118`;
- state-driven policy for COMPLETED/PROCESSING, contradictions, stale edges, graph/Cassandra repair:
  `ResolutionReconciliationPolicy.java:32-114`;
- conditional graph repair via `ensureResolution`:
  `ResolutionReconciler.java:156-183`, `Neo4jGraphConnector.java:357-369`;
- exact identity/fence observation with duplicate-active detection:
  `Neo4jGraphConnector.java:291-339`;
- lease/ownership-aware Cassandra repair:
  `ResolutionReconciler.java:186-218`;
- metrics for repaired/stale/contradiction/errors/skipped/lease-held:
  `ResolutionReconciler.java:61-73`.

Limits:

- `ResolutionGraphPort` defaults are unsupported (`ResolutionGraphPort.java:9-32`); only Neo4j implements them.
- Startup validation is a boolean, not a composition check (`ReconciliationConfig.java:22-33`).
- The policy never mutates a stale active edge; it only detects/logs (`ResolutionReconciliationPolicy.java:60-65`; `ResolutionReconciler.java:220-252`). That is correct for reconciliation, but means stale-edge repair/retraction belongs to the separate maintenance workflow.
- No concurrent multi-reconciler test over the real stack; the existing expiry test is single-worker.
- No test that a second worker holding a healthy lease is preserved across all three consumers in one run.

### Q5. Current state of promotion idempotency

Implemented:

- one transaction merges `:CanonicalRelationAnchor`, `:REL`, `:RelationEvidence`, `:ExtractedRelation`, and `:SUPPORTS`:
  `Neo4jGraphConnector.java:439-518`;
- deterministic canonical relation id and evidence id (`PromotionIds`), with unique constraints for the anchor and evidence:
  `Neo4jGraphConnector.java:709-740`;
- validation gate and exact endpoint re-verification before graph write:
  `RelixCanonicalRelationPromoter.java:32-63`;
- promotion work is claimed/leased before the port call:
  `PromotionConsumer.java:94-116`.

Wiring status: the anchor and evidence model are wired to the runtime promotion path; `#581` fencing applies to `:RESOLVES_TO`, not to the promotion transaction itself.

Gaps:

- `PromotionConsumer` ignores the returned `PromotionResult` and completes the work item even when promotion wrote zero edges because the active resolutions changed
  (`PromotionConsumer.java:108-122`). A stale/zero-write outcome is not surfaced as stale/retry.
- No promotion lost-response test (commit then timeout) in the real-store suite; the current lost-response test is resolution-only.
- No concurrent promotion claim test with two consumers.
- No test that a higher resolution fence arriving between validation and promotion produces an observable outcome rather than a silent zero-write completion.

### Q6. Current divergence lifecycle

- `ProjectionProvisioner` compares Syntology’s active version with stored metadata.
- On mismatch it logs `graph.provisioning.DIVERGENCE_DETECTED`, returns `DIVERGENT`, and leaves `projection_version` unchanged:
  `ProjectionProvisioner.java:81-88`.
- The admin provisioning endpoint returns the outcome; it does not migrate the graph:
  `ProjectionAdminController.java:25-43`.
- There is no migration endpoint/job, no automatic upgrade, and no evidence of an operator workflow.
- This is the correct Phase 1 state: provisioning success and graph migration success are distinct.

**Conclusion:** `DIVERGENT` is currently a terminal, observable, manual state. Phase 2 must not silently resolve it.

### Q7. Untested failure modes

Missing or insufficient evidence:

1. **Promotion lost response after graph commit** — no real-store commit-then-timeout test for promotion.
2. **Concurrent promotion claims** — no two-worker race proving one deterministic `:REL`/evidence set.
3. **Stale validation at promotion time** — no test for zero-write `PromotionResult` being treated as stale/contradictory instead of completed.
4. **Full combined concurrent consumers/reconcilers** — resolution, promotion, and reconciliation racing in one harness; current tests are mostly single-consumer.
5. **Restart mid-promotion** — resolution restart is covered; promotion restart in flight is not.
6. **Reconciler repair of Cassandra-updated/graph-missing** and **graph-present/Cassandra-incomplete** under concurrent workers; only targeted cases exist.
7. **Independent loop enable/disable** and cross-blocking are not exercised as a matrix.
8. **Source change / new version invalidation** — no E2E. See §5.
9. **Divergence operator workflow** — no manual/automated migration test; intentionally out of Phase 2 implementation.

## 2. Dependency resolution

**Decision:** #592 and #589 overlap but are not one serial chain:

- **P2-1 / #592:** completion is a small contract/capability/lint slice. It must merge before final #589 evidence.
- **P2-2 / #589 harness:** may be developed in parallel because the seam is already gone; its final pass must run after P2-1, and its commit must be recorded.
- **P2-3 source-change maintenance:** depends on #592’s graph boundary and on the promotion/reconciliation fixes above. It is the Phase 2 proof point.
- **P2-4 divergence decision:** documentation/operator workflow only; no auto-migration.

The only hard serial edge is **#589 final evidence → post-#592 tree**. #589 can start earlier, but it cannot close earlier.

## 3. Reconciliation correctness

The invariant: **concurrent updates must never corrupt the canonical graph or silently downgrade a newer decision.**

Required work:

1. **Fencing under simultaneous writers**
   - Exercise ≥2 real writers for the same mention/attempt.
   - Assert the higher/equal fence wins, the lower fence is rejected, and an older worker cannot complete Cassandra work.
   - Existing basis: `Neo4jGraphConnector.java:552-580`; `ResolutionReconciliationPolicy.java:82-93`.
2. **Conditional-write conflict resolution**
   - Distinguish REJECTED (stale/permanent), RETRYABLE (infrastructure), and APPLIED.
   - Rejected must produce observable identifiers/metrics; it must not be counted as repaired.
   - Existing basis: `ResolutionReconciler.java:156-183`.
3. **Promotion idempotency under retry**
   - Same relation replay → one `:REL`, one evidence node per evidence identity, one `:SUPPORTS`.
   - A lost response after commit → retry completes without duplicates.
   - Existing basis: `Neo4jGraphConnector.java:439-518`, unique constraints at `709-740`.
4. **Promotion stale outcome**
   - Treat zero-write promotion caused by changed active resolutions as an explicit stale/retryable outcome, not `PROMOTED`.
   - Update `PromotionConsumer.java:108-122` behavior only after the outcome contract is defined.
5. **Observable outcomes**
   - Rejected, superseded, retried, promoted, stale, and contradictory each have distinct counters/log identifiers; no generic “failed” collapse.

## 4. Failure recovery

Build on #595/#610/#612, do not duplicate them.

Required additional coverage:

- restart during promotion after graph commit but before promotion completion;
- process death during reconciliation between graph read, graph write, and Cassandra completion;
- partial promotion failure inside the single Cypher transaction (must roll back all nodes/edges/evidence);
- recovery without duplicate `:REL`, duplicate active `:RESOLVES_TO`, duplicate evidence, or lost evidence;
- healthy lease is never stolen by reconciler/promotion;
- extraction status remains untouched by reconciliation and promotion;
- each loop enabled independently, with the others disabled, and then all enabled together;
- evidence must name the commit/tree, store versions, and failure injection mode.

## 5. Source-change maintenance — the Phase 2 proof point

**Acceptance scenario:** one document is ingested, extracted, resolved, and promoted; then the source is **modified** (same source identity/resource, new source version), not re-created.

Required behavior:

1. New version creates a new source version/content ref and search-projects the changed chunks.
2. Old version’s contributions are invalidated only when no live version still references the content-addressed chunk.
3. Extraction is retried for the changed version; stale in-flight generations cannot write graph state after invalidation.
4. Resolution creates new mentions/decisions for the new version; old active resolutions are superseded, never duplicated.
5. Promotion updates/retains canonical evidence:
   - facts still asserted retain/accumulate live evidence;
   - facts no longer asserted are retired or marked inactive by policy;
   - history/provenance remains queryable; no duplicate canonical edges.
6. Search, Entity Graph, and provenance all show the new source version and no stale live fact.

Current gaps that make this fail today:

- `ExtractionConsumer` processes a version without checking that it is still current before/after chunks
  (`java/synflux/src/main/java/org/synanton/synflux/extraction/ExtractionConsumer.java:82-100`).
- `GraphWriter` derives version-scoped mention/relation ids and never retires the previous version’s graph state
  (`java/synflux/src/main/java/org/synanton/synflux/extraction/graph/GraphWriter.java:75-90,117-131`).
- `SearchProjectionConsumer` upserts on `SourcePublished` and deletes only on `SourceDeleted`; a modified source’s old content ref is not removed
  (`java/synquest/src/main/java/org/synanton/synquest/service/SearchProjectionConsumer.java:87-116`).
- Resolution supersession is per mention identity (`Neo4jGraphConnector.java:558-580`), so new-version mentions do not automatically retire old-version active edges.
- Promotion anchors/evidence have no retraction/expiry workflow (`Neo4jGraphConnector.java:439-518`).
- No E2E test covers update-after-promotion. This is the gap the Phase 2 exit evidence must close.

## 6. Divergence migration

**Decision:** Phase 2 does not implement automatic divergence migration. `DIVERGENT` remains terminal and manually actionable.

Invariants:

- Provisioning never overwrites `projection_version` (`ProjectionProvisioner.java:81-88`).
- Migration is a separate future lifecycle with its own authorization, plan, dry-run, and observability; it must not reuse the provisioning endpoint as an implicit upgrade.
- Operator action for now: investigate Syntology active version vs stored projection, choose a migration workflow when one exists; do not edit metadata by hand.
- A future migration ADR must address: read compatibility, dual-write/rollback, evidence retention, and the post-migration conformance/tree evidence.

## 7. Exit criteria

Phase 2 is complete only with all of the following evidence:

### Tests
- [ ] Two concurrent resolution writers: higher/equal fence wins, lower fence rejected, one active edge.
- [ ] Two concurrent promotion workers: exactly one `:REL`, one evidence identity, one `:SUPPORTS`; lost response retry safe.
- [ ] Zero-write promotion from stale active resolutions produces an observable stale outcome, not `PROMOTED`.
- [ ] Restart during resolution, promotion, and reconciliation; no duplicate/lost state.
- [ ] Higher graph fence never overwritten; healthy lease preserved.
- [ ] Stale active edge with non-RESOLVED decision: metric + identifiers, no mutation.
- [ ] Each consumer/reconciler loop independently enabled/disabled; no cross-blocking.
- [ ] Post-#592 rerun of the full combined matrix, with commit/tree recorded.
- [ ] Source-change E2E: updated document, new version, old active state retired, new evidence visible, provenance intact, no duplicate canonical state.

### Live acceptance
- [ ] A recorded K8s/local-real acceptance artifact (same standard as `docs/acceptance/k8s-activation-2026-11-11.md`) with source status, graph counts, search/Entity Graph/provenance checks, flags, images/tree, and failure-injection transcript.
- [ ] Flags remain false until the combined gate passes; then explicitly enabled with evidence.

### Issue gates
- [ ] #592 closed only when capability composition, startup validation, rule-5 lint, and applicable conformance are green.
- [ ] #589 closed only after the post-#592 combined matrix and activation evidence; not before.
- [ ] Phase 2 marked complete in #634 only after the source-change E2E and acceptance artifact.

## 8. Work sequence (after this plan is reviewed)

1. **P2a-1 (#592 completion, small PR):** `GraphCapabilities` startup validation for reconciliation/promotion; rule-5 boundary test; reconcile #587 issue state; no behavior change.
2. **P2a-2 (#589 harness, parallel PR):** build missing scenarios (promotion lost response, concurrent claims, independent loops) on the current tree; final rerun after P2a-1.
3. **P2b-1 (source-change ADR, reviewed before code):** version fencing, assertion lifecycle, search-index replacement, failure semantics; no implementation until approved.
4. **P2b-2 (source-change correctness):** version fencing before graph mutation; assertion retirement/SUPERSEDED evidence; search-index replacement; STALE status/retry.
5. **P2b-3 (maintenance correctness):** promotion zero-write/stale outcomes and the remaining restart/failure-matrix gaps.
6. **P2c (source-change E2E):** live/local-real acceptance artifact with modified-document scenario.
7. **P2c-2 (divergence):** operator documentation/decision; no auto-migration.

Steps 1 and 2 may run in parallel. Step 3 gates step 4. #589 final evidence remains blocked until P2a-1 is merged.

Do not start Phase 3/5 in parallel. Do not close #592/#589 until evidence exists. Do not treat DIVERGENT as a bug to fix in this phase. Do not merge this plan with the old “2a completes Phase 2” framing.
