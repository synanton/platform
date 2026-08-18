# 01 - synquest - Phase 1 - Hybrid Search Kernel (Java + Lucene PoC)

**Version:** 1.0
**Date:** 2026-07-19
**Status:** Draft for review
**Priority:** 1 of 5 in the query-path Phase 1 series (this is the search foundation everything else calls into).
**Depends on:** [ingestion-pipeline-Phase2.md](./ingestion-pipeline-Phase2.md) Definition of Done - `embedding_content_cache` populated with BGE-base 768-dim vectors.
**Scope:** Serve hybrid (dense + BM25) top-K search over the Phase 2 ingestion output. In-memory Lucene 9.x index built at boot from Cassandra, plus a query-time embedding lookup against the existing vLLM embed endpoint. Single tenant, no ACL, no sharding.

---

## 1. Context and Document Alignment

| Source | Role |
|--------|------|
| [synanton-design-1.18.md §20 `synquest`](../architecture/platform/synanton-design-1.18.md) | Production target - Rust core with Cuckoo ACL, hot-shard rebalancing, region awareness, recall monitoring. Phase 1 implements the **retrieval math only** in Java + Lucene. |
| [ingestion-pipeline-Phase2.md](./ingestion-pipeline-Phase2.md) | Data source - `ingestion_cache.embedding_content_cache` and `ingestion_cache.chunks_payload` are the inputs. |
| [rust/Cargo.toml](../../rust/Cargo.toml) | Placeholder workspace, explicit comment: "the demo does not depend on any Rust code". This plan honours that; a Rust rewrite lives in a later phase. |

**Language choice.** Phase 1 is **Java + Apache Lucene 9.11**. Lucene 9.5+ ships native HNSW dense-vector support (`KnnFloatVectorQuery`) and mature BM25. The design-doc Rust target (§20) migrates in when perf becomes the bottleneck - Phase 1's PoC scale (~10K chunks) fits comfortably in a JVM.

**Explicit non-goals for Phase 1:**

- No Cuckoo ACL filter - single tenant, no ACL.
- No shard versioning, no hot-shard rebalancing - single-shard, single-node.
- No region awareness, no cross-region penalty.
- No recall monitoring, no drift detection.
- No supernode sampling, no multilingual tokenisation (English analyzer only).
- No Rust panic guard (there's no Rust yet).
- No gRPC - REST only in Phase 1; internal callers (`gateway`) use the REST endpoint.
- No incremental index updates - Phase 1 rebuilds the index at boot and on explicit `/reindex` call. Cassandra is the source of truth; Lucene is a build artifact.

---

## 2. Phase 1 in One Sentence

> Given a natural-language query, produce the query's dense embedding via the existing vLLM embed endpoint, execute HNSW top-K on the dense side and BM25 top-K on the lexical side over the Lucene index, fuse them with RRF, and return the ranked hits.

---

## 3. Target Architecture

```mermaid
flowchart LR
  CH[(Cassandra<br/>ingestion-cache)] -->|boot: load chunks + embeddings| IX[Lucene Index<br/>./data/synquest/{tenant}/]
  CLIENT[gateway /<br/>test client] -->|POST /search| SQ[synquest :8083]
  SQ -->|query embedding<br/>POST /v1/embeddings| VEMB[vllm-embed :8001]
  SQ -->|HNSW + BM25 + RRF| IX
  SQ -->|ranked hits| CLIENT
```

**Deployment.** One Spring Boot service on port `:8083`. No new Docker containers - reuses Cassandra + vllm-embed from Phase 2 ingestion.

**Storage.** Lucene index on local disk under `./data/synquest/{tenant}/`. Rebuilt on `POST /reindex` or when the manifest scan detects new `content_ref_id`s since the last build.

---

## 4. Data Contract

**Input:** `POST /search`
```json
{
  "tenant": "demo",
  "query": "supply chain risks",
  "top_k": 20,
  "top_k_dense": 100,
  "top_k_lexical": 100,
  "rrf_k": 60
}
```

**Output:**
```json
{
  "hits": [
    {
      "content_ref_id": "…-uuid",
      "chunk_ordinal": 3,
      "score": 0.0234,
      "score_dense": 0.87,
      "score_lexical": 4.12,
      "rank_dense": 2,
      "rank_lexical": 5,
      "snippet": "…passages of the chunk text…",
      "source_uri": "file:///demo-data/documents/foo.md"
    }
  ],
  "trace": {
    "query_embed_ms": 42,
    "dense_search_ms": 8,
    "lexical_search_ms": 6,
    "fusion_ms": 1,
    "total_ms": 57,
    "index_generation": 4
  }
}
```

**Lucene document schema (per chunk):**

| Field | Lucene type | Source |
|-------|-------------|--------|
| `id` | `StringField` | `{content_ref_id}#{chunk_ordinal}` |
| `content_ref_id` | `StringField` | manifest |
| `chunk_ordinal` | `NumericDocValuesField` | chunks_payload |
| `text` | `TextField` (indexed, stored, analyzed) | chunks_payload.chunk_text |
| `embedding` | `KnnFloatVectorField` (768-dim, `COSINE`) | embedding_content_cache (LZ4-decompress → fp16 → fp32) |
| `source_uri` | `StoredField` | manifest.source_uri |
| `mime_type` | `StoredField` | manifest.mime_type |

Only chunks whose `manifest.state='EMBEDDED'` are indexed.

---

## 5. Module Boundaries

**Owned by `java/synquest/` in Phase 1:**
- Lucene index build + query.
- Query-time embedding call to `synanton-llm-client` (embed base URL).
- RRF fusion.
- `POST /search`, `POST /reindex`, `GET /health`, `GET /index/stats`.

**Not owned:**
- ACL enforcement - Phase 1 has none.
- Rerank - the reranker (Phase 3+) plugs in via `gateway`, not here.
- Result deduplication across `content_ref_id` - kept: multiple chunks from the same doc appear separately. Dedup is a gateway concern.
- Query planning - that's `planner` (03).

---

## 6. Prerequisites

| # | Prereq | Home | Notes |
|---|--------|------|-------|
| P1 | Ingestion Phase 2 DoD met - `state=EMBEDDED` manifest rows exist. | - | Blocking. |
| P2 | Add `java/synquest` to `settings.gradle.kts`. | root | New module. |
| P3 | `synanton-llm-client` bean wired for the embed base URL (already exists from ingestion Phase 2). | shared config | Reuse. |
| P4 | Local writable dir `./data/synquest/` - created by the app on first boot. | dev host | No manual step. |

---

## 7. Task Breakdown

Ordered by dependency. Each task ≤ 2 days for one engineer.

| # | Task | Deliverable |
|---|------|-------------|
| SQ-1 | Create Gradle module; deps: Spring Boot web, Apache Lucene 9.11 (`lucene-core`, `lucene-analysis-common`, `lucene-queries`, `lucene-queryparser`), `synanton-llm-client`, `ingestion-cache` (Phase 1 DAO). | `build.gradle.kts` |
| SQ-2 | Domain records: `SearchRequest`, `SearchResponse`, `Hit`, `SearchTrace`. Located under `org.synanton.synquest.api.dto`. | Java records + tests |
| SQ-3 | `LuceneIndexBuilder` - iterates `ingestion_cache.manifest WHERE state='EMBEDDED'` via `IngestionCacheClient.listManifest(tenant)`; for each ref, reads chunks + embeddings; writes Lucene documents into `./data/synquest/{tenant}/`. Idempotent - uses `IndexWriter` in `CREATE_OR_APPEND`. | Class + test |
| SQ-4 | `IndexGeneration` bookkeeping - a `SegmentInfos` commit user-map records the max `ingested_at` timestamp indexed so far. On boot, only refs later than that get re-scanned. Simple, works for PoC (no delete/update). | Enhancement in SQ-3 |
| SQ-5 | `HybridSearcher` - opens the Lucene index once, holds a `SearcherManager` for near-real-time refresh. `dense(vec, k)` runs `KnnFloatVectorQuery`; `lexical(text, k)` runs `MultiFieldQueryParser` over `text` with BM25 similarity. Both return `TopDocs`. | Class + tests |
| SQ-6 | `RrfFusion` - combines dense + lexical `TopDocs` using Reciprocal Rank Fusion: `score(doc) = Σ 1/(rrf_k + rank_i)`. Returns top `top_k`. Exposes both raw scores and ranks for observability. | Class + test |
| SQ-7 | `QueryEmbedder` - calls `LlmClient.embed(EmbedRequest.of("bge-base-en-v1.5", query))` and normalises the vector (L2 norm) to match BGE convention. | Class + test |
| SQ-8 | `SearchService` - orchestrates SQ-5, SQ-6, SQ-7; assembles `SearchResponse` with trace timings. Bounded thread pool for parallel dense + lexical search. | Service class + tests |
| SQ-9 | REST controllers: `POST /search`, `POST /reindex`, `GET /health`, `GET /index/stats`. `MockTenantFilter` populates `TenantContext` from `X-Tenant` header. | Controllers + integration tests |
| SQ-10 | `application.yaml` with sensible defaults (see §9); `SynquestApplication` boot class; on startup, run `LuceneIndexBuilder.buildIncremental(tenant)` for the `demo` tenant if the index is missing or stale. | Boot + config |
| SQ-11 | E2E test: Testcontainers Cassandra + vLLM (tiny embed model like MiniLM for CI) → seed 20 fake manifest+chunks+embeddings rows → boot synquest → assert `POST /search` returns non-empty hits and correctly-ranked results (planted answer chunk is in top-3). | `SynquestE2EIT` |
| SQ-12 | `/reindex` endpoint drains the current searcher, closes the index, deletes the directory, and rebuilds from scratch. Guarded by a per-tenant mutex to prevent concurrent rebuilds. | Endpoint + test |

---

## 8. Data Flow

For query `"supply chain risks"` against a demo corpus with 500 chunks already embedded:

1. Boot (once) → `LuceneIndexBuilder` scans Cassandra, writes 500 Lucene docs (text + 768-dim vec + metadata). Commit user-map records `max_ingested_at`.
2. Request → `POST /search { query: "supply chain risks", top_k: 20 }`.
3. `QueryEmbedder` → vLLM embed → 768-dim fp32 vector, L2-normalised.
4. Parallel:
   - `HybridSearcher.dense(vec, 100)` → `KnnFloatVectorQuery` returns 100 doc-ids + cosine similarities.
   - `HybridSearcher.lexical("supply chain risks", 100)` → parsed BooleanQuery over `text` field, BM25 similarity, returns 100 doc-ids + BM25 scores.
5. `RrfFusion.combine(dense, lexical, top_k=20, rrf_k=60)` → 20 top hits.
6. Load `snippet`, `source_uri`, `mime_type` from stored fields for the 20 winners.
7. Assemble `SearchResponse` with trace timings; return to caller.

Expected timings on PoC scale (10K chunks, single JVM, 8-core laptop):
- Query embed: 30-60 ms (dominated by vLLM roundtrip).
- Dense HNSW: 3-15 ms.
- Lexical BM25: 2-10 ms.
- Fusion + hydration: < 5 ms.
- Total: ~50-100 ms p95.

---

## 9. Configuration Surface

```yaml
synquest:
  index:
    path: ./data/synquest
    rebuild-on-boot-if-empty: true
    reader-refresh-seconds: 30
  search:
    default-top-k: 20
    default-top-k-dense: 100
    default-top-k-lexical: 100
    default-rrf-k: 60
    dense-similarity: COSINE     # matches BGE
  embedding:
    model: bge-base-en-v1.5
    dim: 768
    normalise-l2: true
  server:
    port: 8083
llm-client:                       # inherited pattern from ingestion Phase 2
  embed:
    base-url: http://vllm-embed:8001/v1
    model: bge-base-en-v1.5
    timeout-ms: 15000
    max-retries: 3
ingestion-cache:
  contact-points: [cassandra]
  port: 9042
  keyspace: ingestion_cache
  local-dc: datacenter1
```

---

## 10. Testing Strategy

- **Unit tests** - `RrfFusion` (fusion math), `QueryEmbedder` (normalisation, retry), `HybridSearcher` (against a tiny in-process Lucene index).
- **Component tests (Testcontainers)** - spin Cassandra + a tiny CPU-friendly vLLM embed model. Seed fake ingestion data with known-answer chunks. Assert search returns the planted answer in top-3.
- **Determinism guardrail** - index build with `IndexWriter` set to a fixed random seed (`RAM_BUFFER_SIZE_MB=16`, single merge policy). Fixed input → identical index bytes.
- **Recall smoke test** - hand-curate 20 (query, expected-doc) pairs from the demo corpus. Assert recall@20 ≥ 0.9. This is a smoke test, not an SLO - real recall monitoring is a later phase.
- **Reindex idempotency** - call `/reindex` twice; assert index_generation increments once (second call is a no-op if content is unchanged).

---

## 11. Risks and Open Questions

| Risk | Mitigation |
|------|------------|
| Lucene 9.x HNSW is younger than Elasticsearch/Weaviate offerings - recall/perf may lag. | Acceptable at PoC scale. `KnnFloatVectorField` uses `ef_construction=100, M=16` defaults; tunable via `application.yaml` later. |
| BGE embeddings must be L2-normalised for cosine to match indexing side. | `QueryEmbedder` normalises; test asserts `||v||₂ ≈ 1.0`. Index-side vectors are also normalised at build time. |
| In-memory index doesn't scale past ~1M vectors on a laptop. | PoC target is 10K-100K chunks; documented in README. Rust migration is the escape hatch. |
| RRF's `rrf_k=60` is a magic default. | Exposed as a request parameter and config default; tunable per-query. |
| Boot-time reindex is slow (~ 30s per 100K chunks). | Async - service starts, index builds in background, `/health` returns `starting` until ready. `POST /search` returns 503 during warmup. |
| Concurrent `/reindex` calls corrupt the index. | Per-tenant mutex; second concurrent call returns 409. |
| Query embedder is a hot dependency - if vLLM embed is down, search is dead. | Phase 1 accepts this; `/health` reports `dependencies: {vllm_embed: unhealthy}` and search returns 503. |

---

## 12. Definition of Done (Phase 1)

Phase 1 is complete when **all** of the following hold on a fresh clone with Phase 2 ingestion DoD met:

1. `./gradlew :java:synquest:bootRun` starts cleanly; `GET /health` reports `starting` then `ready` within 60 s on a 500-chunk corpus.
2. `POST /search { tenant:"demo", query:"…", top_k:20 }` returns a `SearchResponse` with 20 hits, each carrying `content_ref_id`, `chunk_ordinal`, `score`, `score_dense`, `score_lexical`, `snippet`, `source_uri`.
3. For a hand-curated 20-query recall smoke test, recall@20 ≥ 0.9.
4. `POST /reindex` rebuilds from scratch; `GET /index/stats` reports the new generation number, doc count matching Cassandra.
5. p95 total_ms < 200 ms on a 10K-chunk corpus running on developer laptop hardware (i.e. vLLM embed on GPU-1, no batching for query embedding).
6. `./gradlew test` and the Testcontainers component test pass; component test does not require a real GPU (uses tiny embed model).
7. No modifications to `synvault`, `synflux`, `synanton-llm-client`, `ingestion-cache` - all reads go through the existing `IngestionCacheClient` interface.

---

## 13. Follow-on Phases (Signposted)

- **Phase 2 (synquest)** - Cuckoo ACL filter (needs `topology` module), incremental index updates driven by a manifest-change subscription, distributed shard layout.
- **Phase 3 (synquest)** - Rust migration for the hot loop; JNI or gRPC-native boundary; matches design §20.
- **Phase 4 (synquest)** - Recall monitoring, drift detection, hot-shard rebalancing, region awareness.
- **Phase 5 (synquest)** - Multilingual tokenisation stack (§20 v1.1), supernode sampling for graph-adjacent queries.

Each phase's plan lives as its own doc when needed.
