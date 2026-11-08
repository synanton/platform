# Vertical slice — execution plan

Reference acceptance case: **`refresher-algebra-calculus.pdf`** — one
representative document uploaded through the UI, ingested, searchable, and
returning provenance with page + section. The synthetic corpus is retired from the
demo path (see #508).

## Status dimensions

- **Demo Complete** — the happy path works live in the room.
- **Architecture Complete** — contracts frozen, design coherent, no known structural gaps.
- **Operationally Complete** — survives pod restart, node drain, and failure injection.

The slice is Demo Complete and the search projection is now **Architecture Complete**
(#550/#552). Remaining operational gaps are tracked in P1.

**Milestone — Vertical Slice: Demo Complete (achieved 2026-11-06).** New PDF upload
returns `BM25 #1 · Dense #1 · RRF #1`; provenance renders source/chunk SHA-256 plus
page/section; ontology loads 273 entities with `df` badges. Evidence:
`docs/acceptance/live-dense-2026-11-06.md`.

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
- [ ] **Incremental SourceDeleted projection** — today a delete falls back to a full
  tenant rebuild; replace with `deleteDocuments(new Term("content_ref_id", …))`.
- [ ] **Streaming scale test** — 1,000+ chunks through `build()` without materialising the tenant.
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
  `SentenceChunker`); the chunked baseline is unchanged (0.182).

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
| RAKG extraction epic | Open — sentence chunking done; LLM next | P3 |

## Operation

- Demo bring-up: `./scripts/deploy-demo.sh`.
- Resume: `./scripts/start.sh`; quiesce: `./scripts/stop.sh`.
- Browser: `http://<worker>:30080/search` and `/ontology`.
