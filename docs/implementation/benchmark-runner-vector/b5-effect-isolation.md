# B5 effect isolation — vector-engine effect (VEC-B5.3)

**Status:** measured 2026-10-09. Identical workload: 4-chunk mini corpus,
4 self-match gold queries, recall@10, fp32 vectors, HNSW/cosine everywhere.
Corpora, queries, and metric are the same across all three rows — the table
below compares engines, not setups. (The comp-1 demo-docs run is excluded:
different corpus, queries, and metric meaning.)

## Vector-engine effect (rows 1 vs 2 vs 3)

| Engine | Topology | Recall@10 | p50 (ms) | p95 (ms) | Overhead model (B0.3) |
|---|---|---|---|---|---|
| Lucene standalone | `lucene_hnsw` | 1.0 | 2.70 | 31.2 | proportional (graph scales with m + d) |
| Milvus | `milvus_hnsw` | 1.0 | 3.53 | 50.5 | proportional (~1.8× raw) |
| Qdrant | `qdrant_hnsw` | 1.0 | 2.24 | 27.1 | fixed-per-node + raw (640 B/node class) |

Recall is 1.0 everywhere by construction (exact self-match): it validates index
correctness, not embedding quality. No engine is distinguished on recall at
this scale — p50s cluster at 2–4 ms, p95s at 27–51 ms (JVM/GC + RPC noise
dominant at 4 vectors).

**Finding:** on a 4-vector correctness workload, the three engines are
indistinguishable on quality and within 2× on p95 latency (27–51 ms, JVM/GC +
RPC noise dominant at this scale). The B0.3 overhead models (proportional vs
fixed) predict divergence with dimension and scale — untested here, routed to
Phase-5 measurement. No selection signal at this scale; the matrix keeps all
three viable on functional grounds.

## B5.6 sequential vs parallel crossover — measured (BM25-only scope)

10 demo-doc queries × both modes, live synquest, BM25-only (`topKDense=0`).
Identical hit sets: **10/10** — shape identity holds live, not just in unit tests.

| Mode | p50 (ms) | p95 (ms) |
|---|---|---|
| Parallel | 4.5 | 36.0 |
| Sequential | 5.0 | 10.6 |

Read carefully, not triumphantly: the p95 gap is a first-query cold-cache
effect (rb001 ran first in the parallel run at 59 ms vs 12 ms sequential on
warm caches), not a mode effect. Medians are indistinguishable (4.5 vs 5.0).
**No crossover selectivity is identifiable in BM25-only mode** — the dense leg
is empty under both modes, so there is nothing to cross over. Crossover
measurement needs hybrid runs with real embeddings; routed to Phase-5 with the
procedure (per-leg trace timings on both modes) already in place.

## B5.4 metadata-store effect — DEFERRED

Isolating metadata-store effect (2 vs 4, 3 vs 5) requires full hybrid runs per
composition (lexical via metadata store + vector via engine + fusion). Only
single legs exist today. No table faked in its place.

## B5.5 integration vs composition — DEFERRED

Same reason: YDB+YDB vs rows 2–5 and PG+pgvector vs PG+Milvus/Qdrant need
hybrid runs. Deferred with B5.4.
