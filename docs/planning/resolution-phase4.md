# RAKG Phase 4 — canonical resolution (PR #4a–#4d)

**Status:** PR #4a contracts/persistence + PR #4b.1 entity projection landed (2026-11-08); resolution policy not started
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

## PR #4b — ResolutionService

- `SearchAdapterCandidateProvider` uses the existing tenant-scoped
  `SearchEngineAdapter` (no second index).
- `DefaultResolutionPolicy`: exact/normalized match, ontology type compatibility,
  **rank margin** (`best - runnerUp`), deterministic and versioned.
- Search scores are ranking signals, not probabilities; thresholds come from a
  labeled fixture, never ad-hoc “confidence”.
- New policy versions supersede old decisions explicitly.

## PR #4c — canonical promotion

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
