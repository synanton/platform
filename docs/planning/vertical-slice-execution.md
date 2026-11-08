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
(#550/#552). The P0/P1 baseline is complete; the next milestone is **Knowledge
Construction Complete** (RAKG extraction).

**Milestone — Vertical Slice: Demo Complete (achieved 2026-11-06).** New PDF upload
returns `BM25 #1 · Dense #1 · RRF #1`; provenance renders source/chunk SHA-256 plus
page/section; ontology loads 273 entities with `df` badges. Evidence:
`docs/acceptance/live-dense-2026-11-06.md`.

**Next milestone — Knowledge Construction Complete (RAKG #560).** A sentence-chunked
document is extracted by the LLM adapter, resolved against search/ontology, validated,
and committed as typed knowledge with evidence; acceptance is the frozen gold plus the
end-to-end ingestion path. Baseline: regex entity F1 **0.182**; Java Qwen3-4B adapter
entity F1 **0.618** / relationship F1 **0.078** (tuning harness 0.076).

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
- [ ] **Canonical resolution (PR #4)** — retrieval-assisted resolution, `:RESOLVES_TO`, canonical `:Entity` links, then canonical graph edges (#560).

## P2 — Focused graph exploration (#508)

- [ ] **#508** — bounded neighbor expansion. **Partial (ui#31):** search-result → chunk
  graph (50-node cap, click-to-re-root). Entity-rooted entry needs a reverse
  entity→chunk API.

## P3 — Operational reality

- [ ] Survives pod restart / node drain / redeploy.
- [ ] `stop.sh` / `start.sh` cleanly quiesce + resume (security/ui/platform covered).

## Current issue mapping

| Issue / track | Status | P-level |
|---|---|---|
| #508 (graph) | Open — search-result graph done; entity root pending | P2 |
| #500 (platform crashloop) | Open — out of demo path | deferred |
| #538 (TEI via gateway/Envoy) | Open | P1 |
| #547 (incremental Lucene projection) | Closed (#551/#552) | — |
| #550 (engine adapter boundary) | Closed (#552, ADR-021) | — |
| #556 (query-adaptive RRF) | Open — frozen corpus ready; deferred | P1 |
| #558 (extraction gold + baseline) | Closed (#559) | — |
| #506, #528, #536, #537, #539 | Closed | — |
| ADR-022 (extraction engine) | Accepted — Phase B adapter landed | P2 |
| RAKG extraction epic (#560) | Open — adapter/emission/consumer/validated mention graph landed; canonical resolution pending | P2 |

## Operation

- Demo bring-up: `./scripts/deploy-demo.sh`.
- Resume: `./scripts/start.sh`; quiesce: `./scripts/stop.sh`.
- Browser: `http://<worker>:30080/search` and `/ontology`.
