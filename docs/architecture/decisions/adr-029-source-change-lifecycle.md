# ADR-029: Source-change lifecycle — revisioned replacement, not incremental append

**Status:** Proposed for review — 2026-11-12
**Basis:** Phase 2 inspection found that Synanton can construct a canonical graph but cannot yet maintain it when a source document changes. The source, its revisions, the assertions they support, and the graph/search projections are distinct concepts and must have separate lifecycles.

## Review decisions

### Q1 — Atomicity of acceptance

**Design B is chosen, extended through search commit.** A new revision is *accepted* when its generation is registered, but it becomes **current** only after extraction, graph commit, and search commit succeed. Until then, the previous revision remains current for all reads and retrievals.

This deliberately accepts a longer “old knowledge remains visible” window in exchange for never serving an empty or half-replaced revision. A revision with `REVISION_ACCEPTED` but no projection yet is not current.

**Activation is an idempotent `ActivateRevision` step, not part of the search commit transaction.** If the process crashes after search commit but before the current marker flip, the lifecycle state remains `SEARCH_COMMITTED`. A recovery job (or the next lifecycle poll/request) observes that state and safely retries `ActivateRevision`, which is a metadata update guarded by the expected revision/generation. Retrying is a no-op if activation already completed.

### Q2 — Fencing at commit

The generation check is **inside the canonical graph mutation transaction**, not only before calling a port.

- The lifecycle registry in Cassandra owns `accepted_generation` and `current_generation`.
- A per-source `:SourceRevisionFence` node mirrors `accepted_generation` into Neo4j.
- The Cypher transaction that writes staged canonical projection facts starts with a `MATCH` on the fence for `(tenant, source, resource)` and requires `fence.accepted_generation = $generation` before any mutation. If it does not match, the transaction writes nothing and returns `STALE_EXTRACTION`.
- Graph commit checks **accepted** generation because the new revision is not current yet (Design B). A newer accepted revision increments the accepted generation and invalidates older commits.
- Retirement and search commit carry the same fence/generation guard. A stale worker cannot retire assertions, replace search documents, or flip the current marker.
- An application-level pre-check is allowed only as an optimization; it is never the safety boundary.

### Q3 — Retirement semantics

`:REL` is retired only when its **last** active `:RelationEvidence` is superseded. Losing the first of multiple supporting evidence nodes never retires the assertion.

- Evidence is marked `SUPERSEDED`; it is never physically deleted during routine updates.
- A `:REL` with at least one active evidence remains `ACTIVE`.
- An assertion losing all active support becomes `UNSUPPORTED` (historical facts remain queryable with explicit status).
- Conflicting active evidence makes the assertion `CONFLICTED` **and** creates a `REVIEW_REQUIRED` item. It is not auto-resolved.
- Explicit corrections may produce `SUPERSEDED`; that is distinct from loss of support.

### Q4 — Completeness semantics

Absence authorizes retirement only after a **complete, validated** extraction.

“Complete” is defined by the extraction manifest, not by a confidence percentage:

- manifest exists for the revision/generation;
- `expected_chunks > 0`;
- `processed_chunks == expected_chunks`;
- `failed_chunks == 0`;
- extraction/validation status is `COMPLETE`;
- no unresolved parse/chunk failures, and the ontology validation outcome is known.

A percentage threshold is only a review trigger, never authority to retire knowledge.

| Outcome | Behavior |
|---|---|
| `COMPLETE` + validated | Eligible for staged graph/search replacement and atomic activation |
| `PARTIAL` | Preserve old current projection; mark `EXTRACTION_PARTIAL`; retry/review |
| `FAILED` | No graph/search replacement; preserve old current projection |
| `STALE` | Reject entirely, no side effects |
| `CONFLICTED` assertions | Do not retire old assertions; record conflict and review requirement |
| Ontology-invalid | Never commit invalid assertions |

### Q5 — Search visibility

The chosen search strategy is **query-time revision filtering**, not an alias rebuild:

- Every indexed chunk carries `revision_id`/`generation`.
- Reads filter to `current_revision_id` from the lifecycle registry.
- Old chunks remain indexed but are not visible as current because the current revision marker still points at the old revision.
- After graph commit and search commit succeed, a single current-revision marker update activates the new revision. Graph staged facts become active under the same marker; search queries immediately filter to the new revision.
- If search commit fails after graph commit, retry; **never roll back the graph**. The old revision stays current, the graph keeps new staged facts inactive, and the source enters `SEARCH_COMMIT_FAILED`. The failure is observable and reconciliable.
- If activation itself is partially applied, the source enters `ACTIVATION_PARTIAL`; reads remain on the old current revision until the marker mirrors are consistent.
- **Search workers read the authoritative `SourceLifecycle` record from Cassandra** for `current_generation` when filtering. The lookup may be per query or cached for a bounded TTL (documented in configuration; default 0); it is never cached indefinitely in search nodes. A stale local cache must not make an old revision current.

## Decision

### 1. Immutable source revision model

A logical source is separate from its immutable revisions.

| Field | Meaning | Owner |
|---|---|---|
| `tenant_id` | Tenant ownership | `source_versions` |
| `source_id` | Stable logical document identity | `source_versions` |
| `revision_id` | Immutable revision identifier (`source_version_id`) | `source_versions` |
| `generation` | Monotonic source generation; fencing token | `source_versions` |
| `content_hash` | Detect actual content changes | `source_versions` |
| `previous_revision_id` | Revision lineage | `source_versions` |
| `status` | `CURRENT`, `SUPERSEDED`, `STALE`, `DELETED` | `source_versions` |
| `created_at` | Revision creation time | `source_versions` |

The **generation counter lives in Cassandra `source_versions`** alongside today's version row. It is monotonic per `(tenant_id, connector_id, source_id, resource_id)`; timestamps never decide which revision wins. A new revision is registered even when extraction fails, so the fence exists before downstream work starts. Equal content hash is a no-op, not a new generation.

Mapping: `version_id` becomes the immutable `revision_id`; `generation`, `content_hash`, and `previous_revision_id` become required lifecycle fields.

### 2. Staged update pipeline

1. revision detected and registered (`REVISION_ACCEPTED`);
2. extraction produces mentions, candidate assertions, relations, evidence, and manifest;
3. validation covers extraction completeness, ontology, evidence, and resolution;
4. staging writes `:ExtractedMention`/`:EXTRACTED_RELATION`/manifest and staged canonical support without retiring old knowledge;
5. graph commit conditionally stages the new canonical projection against the accepted generation;
6. search commit indexes the new revision with its `revision_id`;
7. activation flips `current_revision_id` and makes staged graph support active;
8. retirement supersedes old-only evidence and propagates.

The old projection remains current through steps 1–6. Failure before 7 leaves it current.

### 3. Provenance-aware assertions

Treat `:REL` as the canonical assertion, `:RelationEvidence` as evidence, and support as many-to-many:

```cypher
(:Entity)-[:REL {canonical_relation_id, status}]->(:Entity)
(:REL)-[:SUPPORTED_BY]->(:RelationEvidence {evidence_id, source_version_id, status})
(:RelationEvidence)-[:EXTRACTED_FROM]->(:SourceRevision)
(:ExtractedMention {source_version_id, assertion_status})
```

Existing `:RelationEvidence.canonical_relation_id` remains for compatibility; `:SUPPORTED_BY` makes the support relationship explicit. `:ExtractedMention` is version-scoped and retained; Phase 2 does not merge mention identities across revisions.

Assertion and support states:

| Term | Definition |
|---|---|
| `ACTIVE` | Has at least one current, validated evidence item. |
| `UNSUPPORTED` | Has zero current evidence; the assertion node remains in the graph as a soft-retired/historical fact. |
| `SUPERSEDED` | Explicitly replaced by a newer assertion or correction (for example, V2 explicitly corrects V1). This is **not** the same as losing one evidence item. |
| `CONFLICTED` | Current evidence disagrees; no automatic winner is selected. |
| `REVIEW_REQUIRED` | A conflict or incomplete evidence set needs an operator/review workflow. |

Evidence losing one of several supports does not by itself change an assertion to `UNSUPPORTED` or `SUPERSEDED`; only losing the last current evidence makes it `UNSUPPORTED`, and only an explicit replacement makes it `SUPERSEDED`.

### 4. Source-version fencing

Every extraction/downstream mutation carries `(tenant_id, source_id, revision_id, generation, event_id)`.

The mutation transaction checks the Neo4j `:SourceRevisionFence.accepted_generation` for the source. If the operation's generation is not the latest accepted generation:

```text
if extraction.generation != source.accepted_generation:
    return STALE_EXTRACTION   # no graph/search/retirement writes
```

A V1 extraction finishing after V2 is accepted is discarded in full. A V1 extraction finishing after V2 is *current* is also discarded. The same guard applies to assertion retirement and search replacement. Stale workers cannot flip the current marker.

### 5. Complete versus partial extraction

The extraction manifest is required:

| Field | Purpose |
|---|---|
| `source_revision_id` | Revision |
| `extraction_run_id` | Run identity |
| `generation` | Fence |
| `expected_chunks` / `processed_chunks` / `failed_chunks` | Completeness |
| `accepted_assertions` / `rejected_assertions` | Outcome accounting |
| `completion_status` | `COMPLETE`, `PARTIAL`, `FAILED`, `STALE` |
| `model_version` / `prompt_version` | Reproducibility |

Only `COMPLETE` + validated can authorize retirement. A partial extraction preserves the old projection and cannot delete valid knowledge.

### 6. Explicit replacement and retirement semantics

| Event / operation | Effect |
|---|---|
| `SourceRevisionAccepted` | New immutable revision registered; old revision still current |
| `ExtractionCompleted` | Manifest/outcomes available; no retirement yet |
| `KnowledgeProjectionCommitted` | New revision's graph facts staged |
| `SourceRevisionCurrent` | Activation marker flipped; new revision current |
| `EvidenceSuperseded` | Old evidence no longer current |
| `AssertionUnsupported` | No current evidence remains |
| `SourceDeleted` | Logical source removed |

`SourceRevisionAccepted` supersedes `SourcePublished` for lifecycle. During migration `SourcePublished` may remain a compatibility projection of the same fact; it must not become a second authority. `SourceSuperseded` cancels an intermediate revision before full processing. `SourceDeleted` remains deletion-only.

### 7. Search index replacement

- index new revision chunks with `revision_id`/generation;
- old chunks remain indexed but hidden by the current-revision query filter;
- replace/delete old chunk attribution only when no other live revision references the content-addressed chunk;
- preserve revision metadata for provenance;
- make replacement retryable and idempotent;
- reject stale indexing using the same generation fence;
- on failure: keep old as current, mark `STALE`, retry/backoff, surface in API/UI; never present old as current.

### 8. Graph and search as a distributed workflow

Neo4j, Cassandra, Lucene and downstream services cannot share one ACID transaction. Do not simulate global exactly-once.

Use transactional outbox, at-least-once delivery, idempotent consumers, per-source ordering/generation fencing, durable processing state, retries, and reconciliation for incomplete projections.

Event envelope minimum:

```json
{
  "event_id": "uuid",
  "tenant_id": "tenant-123",
  "source_id": "source-456",
  "revision_id": "revision-2",
  "generation": 2,
  "content_hash": "def456",
  "event_type": "SourceRevisionAccepted",
  "correlation_id": "workflow-789"
}
```

Consumers must reject stale/duplicate work from the envelope without inferring authority.

### 9. Recommended architecture and boundaries

Organize as a **Source Change Management capability**:

1. **Source Revision Registry** — immutable revisions, accepted/current generations, hashes (Synflux/Cassandra).
2. **Extraction and Validation** — RAKG, mentions, resolution, ontology validation.
3. **Projection Coordinator** — staging, completeness, fencing, conditional commit, activation.
4. **Canonical Graph** — assertions, evidence/support, retirement (Relix).
5. **Search Projection** — revision-aware indexing and visibility (Synquest).
6. **Reconciliation and Observability** — detect/repair incomplete or divergent projections.

Do not create a standalone microservice yet. Establish lifecycle contracts and implement the coordinator inside existing boundaries.

### 10. Acceptance tests, metrics and implementation order

| Scenario | Expected result | States / transition |
|---|---|---|
| New revision, complete extraction | New projection committed | REVISION_ACCEPTED → EXTRACTING → EXTRACTION_COMPLETE → GRAPH_COMMITTED → SEARCH_COMMITTED → CURRENT |
| Same content hash | No unnecessary reprocessing | REVISION_ACCEPTED → CURRENT (no-op) |
| Old extraction completes late | Rejected by fencing; no partial mutation | EXTRACTING → STALE_REJECTED |
| Extraction partially fails | Previous projection preserved | EXTRACTING → EXTRACTION_PARTIAL; old CURRENT |
| Assertion supported by two sources, one changes | Remaining support keeps assertion active | CURRENT → evidence superseded; assertion ACTIVE |
| Assertion loses all current support | Assertion retired/marked unsupported | CURRENT → assertion UNSUPPORTED |
| New revision introduces contradiction | Conflict recorded; no silent overwrite | EXTRACTION_COMPLETE → CONFLICTED/REVIEW_REQUIRED |
| Graph commit succeeds, search update fails | Search repair retryable; STALE observable | GRAPH_COMMITTED → SEARCH_COMMIT_FAILED; old CURRENT |
| Search update succeeds, graph commit fails | Old current preserved; retry no graph rollback | GRAPH_COMMIT_FAILED; old CURRENT |
| Duplicate event | No duplicate graph mutation | idempotent fence check |
| Source deletion | Graph support and search projection both handled | CURRENT → DELETED |
| Worker restarts during replacement | Workflow resumes safely | any committing state → resume/repair |
| Ontology version diverges | Explicit migration required; no implicit upgrade | DIVERGENT/manual; no auto-current |

Metrics: revision-acceptance→graph-commit time; revision→search-visible time; stale extraction rejections; active unsupported assertions; incomplete graph/search projections; activation partial count; reconciliation repair rate.

Implementation order:

1. **Revision identity and fencing** — immutable revisions, generations, stale rejection.
2. **Assertion support lifecycle** — evidence/support, multi-source retention, retirement.
3. **Staged graph projection** — manifest, completeness, conditional commit, activation.
4. **Search revision replacement** — revision-aware indexing, visibility, retry/reconciliation.
5. **End-to-end failure testing** — concurrent revisions, partial extraction, restarts, cross-system recovery.

### 11. Cross-store fence synchronization (amendment 2026-11-12)

The Neo4j fence alone is insufficient because Cassandra is authoritative for `accepted_generation`. A stale worker could pass a lagging Neo4j fence after Cassandra has already accepted a newer generation.

The promotion request must therefore carry the source identity and both generation observations:

- `sourceId`;
- immutable `revisionId`;
- operation `generation`;
- the `cassandraAcceptedGeneration` observed when the request was validated.

The Neo4j mutation transaction reads the per-source `:SourceRevisionFence` and fails closed unless all agree:

```text
cassandraAcceptedGeneration == operation.generation
neo4jFence.generation      == operation.generation
cassandraAcceptedGeneration == neo4jFence.generation
```

Any mismatch throws `StaleGenerationException` before any MERGE/SET. In particular:

- Cassandra ahead of the operation → reject (stale worker);
- Neo4j fence behind the operation → reject; wait for reconciliation to advance it;
- stores disagree → reject (fence drift), even when the operation matches one store.

**Linearization point:** Cassandra acceptance is authoritative; Neo4j fence advancement is derived and must be current before promotion. A newly accepted generation cannot promote until its Neo4j fence is confirmed. Generation advancement is monotonic and idempotent.

**Propagation and reconciliation:** accepted-generation updates are written to Cassandra and `:SourceRevisionFence` as an idempotent dual-write. If the Neo4j write fails, Cassandra remains authoritative and a reconciliation job repairs the fence. The promotion path is fail-closed while the fence is missing, behind, or divergent. Reconciliation is a prerequisite, not an optional follow-up; best-effort dual-write without drift repair is not acceptable.

**Multi-source boundary:** the fence applies to the source-specific evidence/support mutation, not to the canonical assertion. Source A and Source B each have their own source fence; a legitimate evidence addition from a different source must not be rejected because another source has a different generation.

### 12. Promotion linearization point and the weak invariant (amendment 2026-11-12)

The Neo4j transaction that checks `:SourceRevisionFence.accepted_generation` and commits the graph mutation is the **serialization point for promotion**; the fence check and mutation are in one transaction. The Cassandra accepted-generation pre-check is a fast abort, not authorization. The implementation must not perform a Cassandra round-trip inside the Neo4j transaction.

**Weak fence-arbitrated invariant:** a generation-G graph mutation may commit only if `G == G_Neo4jFence` and `G_Cassandra >= G` at pre-check. It may commit after Cassandra has accepted a higher generation when the Neo4j fence has not yet advanced. That is legal serialization: generation 2 first, generation 3 later; the lifecycle then supersedes generation 2.

This holds only if (a) #655 retirement removes all evidence for the source with generation < current, using generation-aware semantics, and (b) #656 search filtering excludes superseded generations. Until both land, generation 2 may be briefly visible after generation 3 acceptance; that is a permitted intermediate state, **not a claim that authority freshness is enforced**. No generation-aware reader/retirement/search filter exists yet; reconciliation cadence is not a reader guarantee, so the window is operationally bounded only by the arrival of #655/#656.

Rejections are distinct: `StaleGenerationException` (fence newer; terminal), `FenceBehindException` (fence behind; retryable after reconciliation), and `UnauthorizedGenerationException` (Cassandra no longer accepts the operation generation; terminal). A CONVERGED reconciliation result is not promotion authorization.

#649 is staged accordingly: #649a proves the graph-level generation race now; #649b proves end-to-end graph/search visibility after #655/#656 land.

### 12.1 Write-lock amendment (2026-11-13)

The §12 statement "fence check and mutation are in one Neo4j transaction" was necessary but not sufficient. A generation-2 transaction could read the fence, stage its mutation, and remain open while generation 3 advanced the fence; generation 2 could then commit after the advance. A read inside the transaction did not serialize the commit against the fence advance.

The serialization point is now the promotion transaction's **fence write-lock held through commit**, with the generation re-checked after lock acquisition and before staging:

- Lock scope: the `:SourceRevisionFence` node for the logical `(tenant, source)`. It is acquired by a no-op property write on `accepted_generation`; the generation is re-checked after the lock is acquired, then all promotion writes run under that lock.
- Contention: Neo4j blocks the contending transaction until the lock holder commits or rolls back. There is no application-level timeout or spin loop in the promotion path.
- Reconciliation: `Neo4jSourceFenceStore.advance` writes the same fence node, so it contends on the same lock. Reconciliation cannot bypass an in-flight promotion commit.
- Crash/retry: the lock is transaction-scoped and released on commit, rollback, or session failure. A retry opens a new transaction and cannot deadlock against its own previous lock.
- Named evidence: `Neo4jGraphCanonicalPortIntegrationTest::concurrentFenceAdvanceCannotOutrunInFlightGenerationTwoPromotion` blocks generation 2 after staging, starts a generation-3 advance, and asserts the advance waits. The sequential stale test remains separate.

**Change control:** dependent tickets are #654, #655, and #656. No conformance tests for those tickets exist yet; this amendment is a prerequisite for their implementation. Existing #648/#666 promotion tests were re-run for this text.

## Lifecycle state machine

Per logical source, the lifecycle record exposes the current state and transition history.

```text
CURRENT (old)
  -> REVISION_ACCEPTED
  -> EXTRACTING
  -> EXTRACTION_COMPLETE
  -> GRAPH_COMMITTING
  -> GRAPH_COMMITTED
  -> SEARCH_COMMITTING
  -> SEARCH_COMMITTED
  -> ACTIVATING (idempotent ActivateRevision)
  -> CURRENT (new)
```

Failure / terminal-for-attempt states:

- `EXTRACTION_FAILED` — retry/manual; old remains current.
- `EXTRACTION_PARTIAL` — old remains current; review/retry; no retirement.
- `GRAPH_COMMIT_FAILED` — retry; old remains current.
- `SEARCH_COMMIT_FAILED` — graph staged; old remains current; retry/alert; never roll back graph.
- `STALE_REJECTED` — older generation finished; no side effects.
- `ACTIVATION_PARTIAL` — current marker mirrors disagree; reads stay on old current until reconciled.
- `SEARCH_COMMITTED` after a crash — recovery/next poll retries idempotent `ActivateRevision`; no graph/search rewrite.

Every state must be queryable via `GET /v1/sources/{sourceId}/lifecycle`, with the revision/generation and transition history. The state machine is the operator contract; logs are not the authority.

### Lifecycle endpoint schema

```json
{
  "source_id": "source-456",
  "accepted_generation": 3,
  "current_generation": 2,
  "active_revision_id": "revision-2",
  "pending_revision_id": "revision-3",
  "extraction_manifest_status": "COMPLETE",
  "graph_commit_status": "COMMITTED",
  "search_commit_status": "IN_PROGRESS",
  "lifecycle_state": "SEARCH_COMMITTING",
  "last_error": null,
  "retry_eligible": true,
  "updated_at": "2026-11-12T12:00:00Z"
}
```

Required fields: `accepted_generation`, `current_generation`, `active_revision_id`, `pending_revision_id`, `extraction_manifest_status`, `graph_commit_status`, `search_commit_status`, `lifecycle_state`, `last_error`, `retry_eligible`, `updated_at`. Nullable fields (`pending_revision_id`, `last_error`) are explicit; they are not omitted. `active_revision_id` is the currently visible revision; `pending_revision_id` is the accepted but not-yet-current revision.

Consequences:
- **Correctness:** old revisions cannot overwrite or reactivate newer knowledge; partial extraction never deletes valid knowledge.
- **Provenance:** evidence/assertions are status-marked, not destroyed.
- **Search:** revision replacement is explicit; stale visibility is observable and retryable.
- **Review:** conflicting evidence is surfaced rather than auto-merged.
- **Migration:** projection/ontology divergence remains separate; this ADR does not auto-migrate it.
- **Rejected:** treating a document update as another ingestion append.
- **Rejected:** using timestamps for ordering or `SourceDeleted` as the update signal.
- **Rejected:** retiring assertions because one extraction omitted them.
- **Rejected:** physically deleting evidence/assertions during routine updates.
- **Rejected:** application-only pre-checks as the fencing boundary.
- **Rejected:** aliasing/rebuilding search as the first strategy; query-time revision filtering is chosen.

## Context

Phase 1 made graph identity, ports, and provisioning explicit. The Phase 2 inspection found the missing operational behavior is source-change maintenance: extraction is version-scoped but not commit-fenced; old graph state is not retired; search projection only removes content on `SourceDeleted`. The system can construct a graph but cannot yet answer what knowledge remains valid after a source changes. This ADR defines the lifecycle before implementation because revision identity, assertion support, staged acceptance, activation, and projection replacement are architectural choices.
