# Vertical slice — execution plan

Reference acceptance case: **`refresher-algebra-calculus.pdf`** — one
representative document uploaded through the UI, ingested, searchable, and
returning provenance with page + section. The synthetic corpus is retired from the
demo path (see #508).

## Status dimensions

- **Demo Complete** — the happy path works live in the room.
- **Architecture Complete** — contracts frozen, design coherent, no known structural gaps.
- **Operationally Complete** — survives pod restart, node drain, and failure injection.
- **Knowledge Construction Complete** — typed, evidenced knowledge is extracted,
  resolved, validated, and committed from ingested chunks (RAKG #560).

The slice is Demo Complete and the search projection is **Architecture Complete**
(#550/#552). The P0/P1 Search + RAKG baseline is complete, and the full RAKG
journey is now proven **both** in local Compose and on the K8s deployment. The
live K8s Entity Graph E2E passed on 2026-11-11; the remaining gate is the
combined consumer/reconciler evidence and the post-#592 rerun (#589).

**Milestone — Vertical Slice: Demo Complete (achieved 2026-11-06).** New PDF upload
returns `BM25 #1 · Dense #1 · RRF #1`; provenance renders source/chunk SHA-256 plus
page/section; ontology loads 273 entities with `df` badges. Evidence:
`docs/acceptance/live-dense-2026-11-06.md`.

**Milestone — Knowledge Construction Complete (locally achieved 2026-11-11).** A
sentence-chunked document is extracted by the LLM adapter, resolved against
search/ontology, validated, and committed as typed knowledge with evidence. The local
Compose smoke proves the complete path: upload → extraction → graph commit → resolution →
validation → promotion → Relix Entity Graph with evidence. Baseline: regex entity F1
**0.182**; Java Qwen3-4B adapter entity F1 **0.618** / relationship F1 **0.078** (tuning
harness 0.076).

## Search engine architecture (post-#552)

```
api controllers ──► SearchService (facade, no Lucene imports)
                        │
                        ├─ SearchEngineAdapter (search/read/lifecycle port)
                        │      └─ LuceneSearchEngine  ──► engine.lucene
                        │             ├─ HybridSearcher (SearcherManager, NRT)
                        │             ├─ RrfFusion
                        │             └─ LuceneIndexBuilder (streamed writes)
                        └─ SearchIndexAdapter (write/lifecycle) ──► EmbeddedLuceneAdapter
```

- **Write path:** `LuceneIndexBuilder.build` streams manifests/chunks through Cassandra
  paging (fetch size 1,000) and relies on Lucene's RAM-buffer flush; incremental
  projection uses `OpenMode.CREATE_OR_APPEND` + `updateDocument(id)`.
- **Read path:** `LuceneSearchEngine` owns the hybrid legs/fusion/rerank and returns
  engine-neutral `SearchDocument`/`SearchResult`; `SearchService` maps API DTOs only.
- **Visibility:** `SearcherManager.maybeRefresh()` after a commit; `reindex` keeps the
  current searcher open, so a full rebuild does not 503.
- **Boundary guard:** `grep -rn org.apache.lucene java/synquest/src/main/.../service` and
  `.../api` returns zero (ADR-021).

## P0 — Baseline (Demo Complete)

- [x] Upload one representative document.
- [x] Ingestion run + operation state visible.
- [x] Search returns chunks on both legs (BM25 + live Dense via TEI).
- [x] Provenance renders page range + section breadcrumb.
- [x] Ontology panel renders (273 total / 31 graph-useful), df badges.
- [x] **#537** — automated vertical-slice regression (ui#28; cold-path CI green).

## P1 — Failure & operability

- [x] **#506** — synquest RWO PVC + RollingUpdate deadlock; `strategy: Recreate`.
- [x] **EWP-2 Migration 3** — typed envelopes, incremental outbox, config gate (#527, #530–#533).
- [x] **#536** — outbox consumer idempotency & recovery (#545).
- [x] **#539** — tenant isolation negative tests for live search + provenance (ui#29).
- [x] **UX polish (503)** — index-updating banner (#525).
- [x] **Live Dense leg** — verified 2026-11-06.
- [x] **#547 / #550 / #552** — streamed rebuild, incremental `updateDocument`, atomic
  `SearcherManager` refresh, and the `SearchEngineAdapter` boundary (ADR-021).
- [x] **Incremental SourceDeleted projection** — tombstone + version manifest resolution,
  live-version safety, `deleteDocuments(new Term("content_ref_id", …))`, full-rebuild
  fallback (#561).
- [x] **Streaming scale test** — 1,200 chunks through `build()` into exactly 1,200
  searchable documents (`LuceneScaleTest`).
- [ ] **#538** — route demo TEI via gateway/Envoy; drop the direct NetworkPolicy exception.
- [ ] Projection lag and retrieval-failure visibility in the UI.

## Retrieval benchmark (validation track)

- `docs/research/retrieval-benchmark-results.md` — Run A (recorded q25, bge-base: RRF
  0.859 nDCG > BM25 0.824 > Dense 0.805), Run B (bge-small 384: BM25 wins; Dense harms
  RRF), Run C (decoupled engine, reconstructed corpus: not a byte-faithful replay —
  original 81-chunk tenant is gone).
- Frozen corpus for future runs: `evaluation/retrieval-corpus/` (`demo-fixed`, 82
  chunks + 25 queries) with `MANIFEST.sha256`; the harness gates runs via
  `retrieval-eval check-corpus` / `evaluate --corpus-manifest ...`.
- **#556** — query-adaptive weighted RRF (EXACT/CONCEPTUAL/MIXED) — frozen corpus
  now exists; still deferred (user).
- **#558** — extraction gold + Phase A regex baseline (entity F1 0.182; closed).
  RAKG Phase 0 sentence chunking landed (`strategy=sentence`, shared
  `SentenceChunker`); the chunked regex baseline is unchanged (0.182).
- **RAKG Phase B tuning** — Qwen3-4B (`qwen3-extraction` on `vllm-extraction`) reaches
  entity P/R/F1 **0.636/0.600/0.618**, type **0.717**, relationship F1 **0.076** on
  the frozen gold; the Java adapter reproduces **0.618 / 0.078** (type 0.697, 33
  relation candidates). ADR-022.

## Knowledge extraction (RAKG)

- [x] **Gold + regex baseline** — 5 docs / 70 mentions / 44 relationships, entity F1 0.182 (#558).
- [x] **Sentence-boundary chunking** — `strategy=sentence`, shared `SentenceChunker` (Phase 0).
- [x] **LLM prompt tuning** — Qwen3-4B vLLM, entity F1 0.618 / relationship F1 0.076 on the frozen gold.
- [x] **`LlmExtractionAdapter`** — Java adapter with surface-form offset recovery + synthetic endpoints, behind `EXTRACTION_PROVIDER=regex|llm` (ADR-022). Live E2E: entity F1 **0.618** / relationship F1 **0.078** (33 candidates, type 0.697).
- [x] **Preview endpoint + Playwright spec** — `POST /api/v1/extraction/preview` (tenant-verified, no persistence); gated `test/e2e/tests/extraction-preview.spec.ts`.
- [x] **`ExtractionRequested` emission** — second event slot on `source_versions`, atomic with `SourcePublished`; `ExtractionRequested@1` REPLAY_SAFE; `search_projected_at` readiness gate (PR #1).
- [x] **Async extraction consumer** — bounded `@Scheduled` poller, sequential LLM calls, `PENDING → EXTRACTING → KNOWLEDGE_READY/EXTRACTION_FAILED`, per-chunk result stub in Cassandra; `extraction_status` on the source status API (PR #2). Design: [`extraction-consumer-phase2.md`](extraction-consumer-phase2.md).
- [x] **Validated graph persistence (PR #3)** — Syntology relation validation, Relix `GraphWritePort` for `:ExtractedMention` + validated `:EXTRACTED_RELATION`, deterministic ids, chunk/document offsets, `graph_status`/counters. Contract: [`extracted-mention-graph-contract.md`](extracted-mention-graph-contract.md).
- [x] **Graph hardening (PR #3.5)** — verified-JWT Relix writes, independent `GraphCommitConsumer` retry/backoff, evidence-id authority, actual persisted counts, Neo4j uniqueness + concurrent test, transitive type inheritance.
- [x] **Resolution contracts/persistence (PR #4a)** — `ResolutionOutcome`/`ResolutionLifecycle`/`ResolutionPolicy`/`ResolutionGraphPort`, V22 resolution state + `resolution_results`, V23 `relation_promotion_results`; source status returns `resolution_status` (PENDING). Zero resolution behavior.
- [x] **Entity projection (PR #4b.1)** — shared Lucene index `doc_type`, `EntitySearchDocument`, `upsertEntities`, entity/chunk segregation, manual Neo4j→Lucene loader.
- [x] **Candidate provider (PR #4b.2)** — `SearchAdapterCandidateProvider` over entity search + Relix `CanonicalEntityLookup`; stale drop, retryable failures, raw retrieval score/matched field/strategy.
- [x] **Resolution policy (PR #4b.3)** — `DefaultResolutionPolicy` with absolute floor + rank margin + type compatibility, reason codes, V24, 10-case fixture (precision 1.000, recall@10 1.000, coverage 0.300), no graph writes.
- [x] **`:RESOLVES_TO` persistence (PR #4c.1)** — active/superseded lifecycle, deterministic resolution id, tenant-safe fail-closed writes, history preserved; no canonical promotion.
- [x] **Canonical relation validation (PR #4c.2)** — active-resolution endpoints, Cassandra evidence re-check, Syntology pinned-ontology gate, `relation_promotion_results` persistence; no `:REL`/`:SUPPORTS` writes.
- [x] **Canonical `:REL` promotion + `:RelationEvidence` (PR #4c.3)** — VALID-only gate, one semantic edge per triple, deterministic evidence nodes, idempotent replay, zero writes on missing/superseded/cross-tenant endpoints.
- [x] **Concurrency fencing (PR #4c.4)** — `:ResolutionSlot` locks, fencing tokens, unique anchor/evidence/relation constraints, 10-thread resolution/promotion tests; closes #576.
- [x] **Recoverable resolution/promotion consumers (PR #4d.1)** — LWT claims with lease/owner, attempt fencing, explicit failure classes, independent `ResolutionConsumer`/`PromotionConsumer`, V25 claim schema, default-disabled flags, retry/terminal/metrics tests.
- [x] **Crash reconciliation (PR #4d.2 / #578)** — Cassandra-driven state matrix, exact resolution/entity/fence checks, owner-guarded repair, higher-fence protection, stale-edge detection-only with metrics/identifiers; `relix.reconciliation.enabled=false`. Retraction policy remains #584.
- [x] **Search-generation propagation (#577)** — Lucene generation → candidate → decision → Cassandra `resolution_results.search_generation` (V26) → Neo4j `:RESOLVES_TO.search_generation`; fixture and real-store assertions added. **Evidence retraction** remains deferred.
- [x] **Validation producer (#583)** — `RelationPromotionProducer` invokes the 4c.2 validator only after both relation endpoints have exactly one active resolution; existing promotion results are never re-opened. Local activation PR #601.

## Local Runtime Activation (achieved 2026-11-11)

PR #601 is the local activation fix set. It adds Neo4j, Relix, Syntology, mock
extraction, entity seeding, and the full-pipeline smoke to the isolated
`local-real-demo` Compose profile.

Evidence:

```text
PASS: local RAKG pipeline reached a queryable entity graph with evidence
entity-auxiliary-coolant-loop -> tested_every -> entity-ninety-days
evidence chunk: 70c5bdc2-1c4e-5109-a10e-2711d1ba978d:0
```

The activation fixes include Jackson 2.18.11 alignment, Relix/Synflux snake_case
DTOs, service JWT for Syntology validation, the Synquest resolution-consumer
property prefix, Syntology's invalid V3 partial index, and the #583 producer. These
are runtime defects found by connecting the real components; K8s activation should
now be deployment configuration, not code debugging.

## P2 — Focused graph exploration (#508)

- [x] **#508 backend** — `GET /v1/entities/{entityId}/neighbors` returns a bounded
  one-hop canonical neighborhood (max 50), outgoing/incoming `:REL` relations, and
  `:RelationEvidence` provenance; string canonical IDs; JWT tenant scope.
- [x] **#508 UI** — entity-rooted graph tab, directional SVG, evidence panel, and
  mocked Playwright coverage merged in #598.
- [x] **#599 live E2E** — passed against the deployed K8s UI/Relix on 2026-11-11
  (25.1s); see [`docs/acceptance/k8s-activation-2026-11-11.md`](../acceptance/k8s-activation-2026-11-11.md).

## Phase 3 — K8s Activation & Demo Readiness

1. **#589** — live journey is green, but the full combined consumer/reconciler
   evidence bundle and post-#592 rerun remain; flags are currently enabled on the
   deployed demo stack.
2. **#599** — complete (live Entity Graph E2E green).
3. **#592 post-migration rerun** — #592 is still open; rerun the gate on the
   post-#592 tree when it lands.

## P3 — Operational reality

- [ ] Survives pod restart / node drain / redeploy.
- [ ] `stop.sh` / `start.sh` cleanly quiesce + resume (security/ui/platform covered).

## Current issue mapping

| Issue / track | Status | P-level |
|---|---|---|
| #508 (graph) | Closed — backend #597, UI #598 | P2 |
| #599 (live Entity Graph E2E) | Closed — K8s live E2E passed 2026-11-11 | P2 |
| #589 (K8s activation) | Open — live journey green; combined fault-injection/post-#592 rerun outstanding | P0 |
| #578 (crash reconciliation) | Open — implementation landed; operational proof still owed | P1 |
| #570 (VALIDATING lease expiry) | Open | P2 |
| #538 (TEI via gateway/Envoy) | Open — deferred post-activation | P1 |
| #556 (query-adaptive RRF) | Open — deferred post-activation | P1 |
| #500 (platform crashloop) | Open — out of demo path | deferred |
| #547 (incremental Lucene projection) | Closed (#551/#552) | — |
| #550 (engine adapter boundary) | Closed (#552, ADR-021) | — |
| #558 (extraction gold + baseline) | Closed (#559) | — |
| #560 (RAKG epic) | Closed — locally proven end-to-end | — |
| #583 (validation producer) | Closed — PR #601 | — |
| #238 (Jackson alignment) | Closed — PR #601 | — |
| #467/#466 (synthetic pool rebuild + grading) | Closed — superseded by #558 gold | — |
| #472 (old graph visualization plan) | Closed — superseded by #508 | — |
| #594/#457/#473 (stale handoff/K8s checkpoints) | Closed — superseded by #589/runbook | — |
| #506, #528, #536, #537, #539 | Closed | — |
| ADR-022 (extraction engine) | Accepted — Phase B adapter landed | P2 |

## Operation

- Demo bring-up: `./scripts/deploy-demo.sh`.
- Resume: `./scripts/start.sh`; quiesce: `./scripts/stop.sh`.
- Browser: `http://<worker>:30080/search` and `/ontology`.
