# RAKG Phase 4 — canonical resolution (PR #4a–#4d)

**Status:** PR #4a–#4b.3 + #4c.1 `:RESOLVES_TO` + #4c.2 validation + #4c.3 canonical promotion landed (2026-11-08); no consumer yet
**Issue:** #560
**Prerequisites:** PR #569 graph hardening (verified-JWT writes, independent graph retry);
#570 tracks lease-based VALIDATING recovery (P2, out of PR #4 scope). Graph writes stay disabled in the demo
stack until the full graph path is exercised.

## Lifecycle separation (do not overload)

| Dimension | Values | Meaning |
|---|---|---|
| `extraction_status` | PENDING / EXTRACTING / KNOWLEDGE_READY / EXTRACTION_FAILED | LLM extraction + per-chunk persistence |
| `graph_status` | PENDING / VALIDATING / GRAPH_COMMITTED / PARTIAL / FAILED | extracted mention/relation persistence |
| `resolution_status` | PENDING / RESOLVING / COMPLETED / PARTIAL / FAILED | canonical resolution + promotion |

`COMPLETED` means **all eligible mentions reached a terminal outcome** —
RESOLVED / AMBIGUOUS / UNRESOLVED — not that every mention resolved.
`PARTIAL` means some mentions are `REVIEW_REQUIRED` (or otherwise non-terminal).
Extraction, graph persistence, and resolution are independent.

## PR #4a — contracts and persistence (this PR)

Shared contracts (`org.synanton.common.resolution`):

- `ResolutionOutcome`: RESOLVED, AMBIGUOUS, UNRESOLVED, REVIEW_REQUIRED.
- `ResolutionLifecycle`: PENDING, RESOLVING, COMPLETED, PARTIAL, FAILED.
- `ResolutionCandidate`, `ResolutionContext`, `ResolutionDecision` (best/runner-up
  scores, strategyVersion, ontologyVersion, diagnostics).
- `ResolutionPolicy` (deterministic, versioned).
- `ResolutionCandidateProvider` (implemented over `SearchEngineAdapter` in 4b).
- `ResolutionGraphPort` (implemented by Relix in 4c; no writes in 4a).

Persistence:

- V22: `source_versions.resolution_status/started_at/completed_at/attempt_count/next_attempt_at/counters`
  and `resolution_results` (one durable decision per mention/version).
- V23: `relation_promotion_results` (per relation/version).
- Status API exposes `resolution_status`; 4a returns PENDING everywhere.

**Zero behavior change:** no search calls, no `:RESOLVES_TO`, no promotion,
no resolution consumer.

## PR #4b.1 — Synquest entity projection (landed)

- `SearchDocumentType` (CHUNK, ENTITY) and `doc_type` on every Lucene document.
- `EntitySearchDocument` (entityId, label, aliases, type, description, optional embedding)
  projected by `SearchIndexAdapter/SearchEngineAdapter.upsertEntities`.
- Entity documents live in the **existing** Directory/SearcherManager; no second index.
  Both the lexical and dense legs are FILTERed to `doc_type`, so chunk and entity
  result sets cannot cross. A full chunk rebuild deletes only CHUNK documents;
  entity docs survive.
- Manual loader: `tools/entity-projection/project_entities.py` (Neo4j HTTP →
  `POST /v1/search-projection/entities`; verified tenant JWT).
- Tests: entity/chunk segregation, dense-entity retrieval, and entity survival
  across a full chunk rebuild.
- **Deploy note:** pre-4b.1 chunk documents have no `doc_type`; run a chunk
  reindex after deploying so they are eligible for CHUNK searches.
- Neo4j remains the source of truth; the resolver verifies candidates in 4b.3.

## PR #4b.2 — Candidate provider (landed)

- `SearchAdapterCandidateProvider` (stateless, bounded to 10): entity search via
  the shared index (`SearchFilter(type=ENTITY)`), then **Relix source-of-truth
  verification** through `CanonicalEntityLookup`.
- `CanonicalEntityLookup.getActiveEntityType(tenant, entityId)`:
  Neo4j-backed `RelixCanonicalEntityLookup` + `GET /v1/entities/{id}/type`
  (verified JWT). 404 = stale projection → drop; unavailable store →
  `RetryableProcessingException`.
- Candidates carry raw `retrievalScore`, `matchedField` (label/alias/description),
  and `retrievalStrategy=RRF`; no confidence percentages and no policy decisions.
- Search/Neo4j failures throw `RetryableProcessingException` (never an empty
  "no match"); no candidates → empty.
- Tests: exact, alias, chunk exclusion, tenant isolation, stale projection,
  fused RRF, infrastructure failure.

## PR #4b — ResolutionService

- `SearchAdapterCandidateProvider` uses the existing tenant-scoped
  `SearchEngineAdapter` (no second index).
- `DefaultResolutionPolicy`: exact/normalized match, ontology type compatibility,
  **rank margin** (`best - runnerUp`), deterministic and versioned.
- Search scores are ranking signals, not probabilities; thresholds come from a
  labeled fixture, never ad-hoc “confidence”.
- New policy versions supersede old decisions explicitly.

## PR #4b.3 — DefaultResolutionPolicy (landed)

- Deterministic, pure policy over `ResolutionContext` candidates; no DB writes.
- Signals: absolute retrieval floor **and** runner-up margin **and** type
  compatibility. Unknown proposed type → REVIEW_REQUIRED; incompatible type →
  UNRESOLVED; weak top → UNRESOLVED; close top/runner → AMBIGUOUS.
- `TypeCompatibility` port with conservative `ExactTypeCompatibility` default.
- Versioned config (`resolution.policy-version`, absolute/margin thresholds) and
  reason codes persisted via V24 `resolution_results.reason_code`.
- **RRF is not a probability.** The initial 0.75/0.15 guesses are meaningless in
  RRF space; the measured fixture calibrates `absolute=0.02`, `margin=0.001`.
- Fixture: `evaluation/resolution-fixture.jsonl` (10 cases: exact, alias,
  abbreviation, semantic, near-duplicate, type mismatch, unknown type, no match,
  context-dependent, tenant isolation).
- Evaluation (real Lucene + real Neo4j): candidate recall@10 **1.000**,
  resolution precision **1.000**, coverage **0.300**, ambiguous 0.300,
  unresolved 0.300, review 0.100. No `:RESOLVES_TO` or `:REL` writes.
- The fixture is a contract test, not a calibrated benchmark; a larger held-out
  set is required before broad automatic promotion.

## PR #4c.1 — `:RESOLVES_TO` lifecycle (landed)

- Relix Neo4j `upsertResolution`: only `RESOLVED` decisions create an **ACTIVE**
  `(:ExtractedMention)-[:RESOLVES_TO]->(:Entity)` link.
- Both endpoints are MATCHed tenant-scoped: missing canonical entity, missing
  mention, or cross-tenant target fails closed (no link, exception).
- Replaying a decision (same deterministic `resolution_id`) is idempotent.
- A new policy version/target supersedes the previous active edge
  (`active=false`, `status=SUPERSEDED`, `superseded_at`), preserving history;
  exactly one active link remains.
- Edge metadata: resolution id, tenant, source version, mention, canonical entity,
  policy version, retrieval strategy, best/runner scores, ontology version,
  decision/superseded timestamps.
- `POST /graph/resolutions` behind the verified tenant JWT.
- Tests: v1 → one active; v2 → one active + one superseded; replay; non-resolved
  no link; missing entity/cross-tenant fail closed; endpoint auth/tenant checks.
- **Not in 4c.1:** canonical relation validation/promotion (4c.2/4c.3), concurrent
  promotion and crash-recovery orchestration (4c.4).

## PR #4c.2 — canonical relation validation (landed)

Validation-only, no graph mutation. Resolution validity is not relation validity;
both endpoints resolved is necessary but not sufficient.

Flow:
1. Load the extracted relation/provenance (`:EXTRACTED_RELATION` projection) and the
   authoritative ACTIVE resolutions for its endpoints from Relix.
2. Require exactly one active resolution per endpoint; missing/multiple/cross-tenant
   → INVALID.
3. Re-check the Cassandra extraction evidence for the evidence chunk/predicate;
   missing evidence → INVALID.
4. Use the canonical entity **types from the active resolutions** (never types from
   the extracted relation) and call Syntology relation validation against a pinned
   ontology version.
5. Persist `relation_promotion_results` (`VALID`/`INVALID`, canonical endpoint ids,
   reason, evidence JSON, strategy/ontology versions, timestamp).
6. Syntology/Relix/Cassandra infrastructure failures throw
   `RetryableProcessingException`; they are never persisted as INVALID.

Backlog (tracked):
- #576 concurrent `:RESOLVES_TO` active-link uniqueness (4c.4)
- #577 search-generation propagation into decisions/edges
- #578 Cassandra↔Neo4j crash reconciliation (4d)

## PR #4c.3 — canonical promotion (landed)

- `promoteRelation(CanonicalRelation)` in Relix; the promoter consumes only a
  **VALID** `relation_promotion_results` row and rejects mismatched/invalid rows.
- One Neo4j write transaction re-verifies both `:RESOLVES_TO {active:true}` links
  and the exact canonical endpoints, then:
  - `MERGE (:Entity)-[:REL {canonical_relation_id, predicate, tenant_id, ontology_version}]->(:Entity)`
  - `MERGE (:RelationEvidence {evidence_id, ...})`
  - `MERGE (:ExtractedRelation {relation_id})-[:SUPPORTS]->(:RelationEvidence)`
  - returns `relationsCreated`, `evidenceCreated`, `evidenceMatched`.
- IDs:
  - `canonical_relation_id = sha256(tenant | subjectEntityId | normalizedPredicate | objectEntityId)`
  - `evidence_id = sha256(tenant | sourceVersionId | extractedRelationId | evidenceChunkId | offsetStart | offsetEnd)`
- One semantic `:REL` per canonical triple; a second document adds evidence and
  `:SUPPORTS`, never a second `:REL`; replay is idempotent.
- Missing/superseded/cross-tenant active resolutions return zero writes; an invalid
  validation row fails before the graph; a Neo4j transaction failure is wrapped as
  `RetryableProcessingException` and the single statement rolls back.
- No consumer, concurrency fencing, search-generation propagation, crash
  reconciliation, or evidence retraction in this PR.

### 4c.3 evidence model (pinned)

Neo4j cannot attach a relationship to another relationship. The canonical edge is
still `(:Entity)-[:REL {canonical_relation_id, predicate}]->(:Entity)`, but evidence
is a node:

```
(:ExtractedRelation {relation_id})-[:SUPPORTS]->
(:RelationEvidence {
  canonical_relation_id,
  source_version_id,
  extracted_relation_id,
  evidence_chunk_id
})
```

One canonical semantic `:REL` edge per triple; multiple independently identifiable
`:RelationEvidence` nodes; idempotent evidence insertion; no mutable
`source_relation_id` overwrite.

- `:ExtractedMention` → `:Entity` via `:RESOLVES_TO` (stores strategyVersion,
  best/runner-up scores, ontologyVersion).
- A canonical `(subject)-[:REL {predicate}]->(object)` is created only when both
  endpoints resolve **and** Syntology validates the canonical triple.
- Unresolved endpoints/predicates/validation failures leave the extracted relation
  unpromoted. Evidence is retained on the extracted edge; multiple assertions for
  the same canonical triple accumulate evidence rather than overwrite it.
- No silent canonical `:Entity` creation; no cross-tenant promotion.

## PR #4d — ResolutionConsumer

- Mirrors `GraphCommitConsumer`: independent scheduled poll, LWT claim, bounded
  batches, attempt/backoff, no LLM re-call.
- Retries retrieval/Cassandra/Neo4j/Syntology failures independently.
- Reconciles crash-after-Cassandra-decision/before-Neo4j-write and the inverse.
- Never repeats completed decisions; supersession is audited (`superseded_at`),
  never destructive.

## Acceptance gates for PR #4

- Tenant isolation for retrieval and promotion.
- High-confidence compatible candidate → RESOLVED.
- Two close candidates → AMBIGUOUS (rank margin).
- Incompatible/unknown type → not auto-resolved.
- Replaying a decision → one active `:RESOLVES_TO`.
- New policy version supersedes the old decision.
- Unresolved endpoints never produce canonical relations.
- Every canonical relation traces to extracted evidence.
- Crash windows reconcile (Cassandra→Neo4j and Neo4j→Cassandra).
- Two consumers cannot establish contradictory active resolutions.
- Resolution never silently creates `:Entity`.
- Measured resolution precision, recall, ambiguity rate, unresolved rate on a
  labeled dataset before broad automatic promotion.

## Out of scope

Automatic entity creation, human-review UI, fine-tuning, cross-tenant resolution,
new workflow infrastructure, DLQ purge, per-tenant GPU fairness.
