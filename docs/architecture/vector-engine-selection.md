# Vector Engine Selection Framework (VEC-B6.2)

**Status:** Draft for review — requires architecture + legal sign-off before it
steers any production decision. Unreviewed sections are marked; nothing here is
approved guidance yet.
**Inputs:** VEC-B0.1 license matrix · VEC-B0.2 deployment modes ·
VEC-B0.3 hardware profiles · VEC-B0.4 context profiles · VEC-B5 measured cells.
**Method:** functional columns carry measured numbers where runs exist and
explicit `unmeasured` flags elsewhere (B6.1 rule — a blank cell is a lie).

---

## 1. Decision matrix

### 1a. Non-functional columns (from B0, full detail in `vector-*.md`)

| Engine | License | Runtime | Managed (3-cloud) | Embedded (prod) |
|---|---|---|---|---|
| PostgreSQL + pgvector | PostgreSQL, permissive | In-process (SQL ext) | ✅ all three | ❌ |
| YDB | Apache-2.0 | In-process (native) | Yandex only | ❌ |
| Cassandra + Lucene | Apache-2.0 | In-process (JVM lib) | ⚠️ (no Lucene in managed) | ❌ |
| Milvus | Apache-2.0 | External service | ✅ (Zilliz) | ❌ |
| Qdrant | Apache-2.0 | External service | ✅ (Cloud) | ⚠️ dev-only |
| Tantivy | MIT (sidecar) | External service | N/A | ⚠️ sidecar, not in-process |
| Lucene standalone | Apache-2.0 | In-process (JVM lib) | ⚠️ via search services | ✅ |
| Weaviate | BSD-3-Clause + proprietary wl/ | External service | ✅ | ⚠️ dev-only |
| Vespa | Apache-2.0 | External service | AWS/GCP only | ❌ |
| OpenSearch | Apache-2.0 | External service | AWS only | ❌ |
| Quickwit | Apache-2.0 | External service | ⚠️ managed-Rust path | ❌ |

License gate: Elasticsearch (SSPL/ELv2/AGPL) excluded from managed-service
contexts — legal sign-off required before any use. No universe engine carries
a managed-service restriction (11/11 LICENSE files read 2026-10-07/08).

### 1b. Functional columns (measured B5 cells; everything else flagged)

| Engine | Recall (measured) | Latency (measured) | Overhead model | Status |
|---|---|---|---|---|
| Lucene standalone | 1.0 (correctness, 4-vec self-match) | p50 2.7 / p95 31 ms | proportional | measured, small-scale |
| Milvus (HNSW) | 1.0 (correctness) | p50 3.5 / p95 50.5 ms | proportional ~1.8× | measured, small-scale |
| Qdrant (HNSW) | 1.0 (correctness) | p50 2.2 / p95 27.1 ms | fixed 640 B/node | measured, small-scale |
| PG+pgvector | 1.0 (lexical legs) | p95 21.6 ms | `unmeasured` (HNSW figure unpublished) | partial |
| YDB | 1.0 (lexical legs) | p50/p95 19.8/40.8 ms | `unmeasured` (IVF, cache-bounded) | partial |
| Cassandra+Lucene | `unmeasured` (recall; BM25 latencies p50 4.5/p95 36 ms) | BM25-only | proportional | partial |
| Tantivy / Quickwit / Weaviate / Vespa / OpenSearch | `unmeasured` | `unmeasured` | per B0.3 | unmeasured |

Recall here means **index correctness** (exact self-match retrieves itself),
not embedding quality — dense quality needs real embeddings (unavailable;
mock-constant vectors prove plumbing only). Latency at 4-vector scale carries
JVM/GC/RPC noise; treat p95 gaps under ~2× as ties.

### 1c. What the functional evidence does and doesn't support

- Supports: all three measured HNSW engines are correct; Lucene parity with
  Cassandra+Lucene on identical queries (R3 attribution resolved); PG/YDB
  lexical legs at recall 1.0 with real latencies.
- Does NOT support: any quality ranking between engines, any scale claim, any
  metadata-store or integration effect (B5.4/B5.5 deferred — no hybrid runs),
  any hybrid-mode crossover (BM25-only scope).

---

## 2. Per-context recommendations (with functional evidence)

| Context | Recommended | Alternative | Functional note |
|---|---|---|---|
| Cloud-managed | PostgreSQL + pgvector | Qdrant Cloud | PG lexical measured (recall 1.0); HNSW figure unpublished — capacity planning carries uncertainty |
| Cloud-self-hosted | Qdrant | PG + pgvector | Qdrant correctness + latency measured; simplest ops in class |
| On-prem | Qdrant | PG + pgvector | Same as above + air-gap suitability (no object-store requirement) |
| Docker | Qdrant | pgvector image | Single container, no sidecars |
| Embedded | Lucene standalone | Tantivy sidecar (non-JVM) | Lucene correctness measured; merge-time heap spikes are the known risk |

Each default is justified by non-functional fit first (B0.4 record) with the
measured cells above as supporting — not deciding — evidence. Where functional
cells are `unmeasured`, the recommendation says so rather than implying data.

## 3. Sources and vintages

- License: 14 LICENSE-file reads, 2026-10-07/08 (11 universe + pgvectorscale,
  pgvecto.rs, Quickwit; TSL hypothesis refuted).
- Deployment: vendor docs + plan seeds (VEC-B0.2 artifact).
- Hardware: vendor formulas/blogs + computed cells (VEC-B0.3 artifact);
  Vespa anchor 2022-06-08 flagged for re-verification.
- Functional: B5 run artifacts (this repo `runs/`, local-only) + `../implementation/benchmark-runner-vector/b5-effect-isolation.md`.

## 4. Follow-ons (VEC-B6.4 — owners TBD, filed here until a tracker home exists)

| ID | Item | Unblocks | Owner |
|---|---|---|---|
| F-1 | Real embeddings (vLLM/GPU or provider) for dense-leg quality | Recall quality everywhere; hybrid crossover | TBD |
| F-2 | Full hybrid runs per composition (B5.4/B5.5) | Metadata-store + integration effects | TBD |
| F-3 | Gold-query annotation vs live tenants (`retrieval-eval inspect`) | Recall on demo corpus (comp-1 style runs) | TBD |
| F-4 | Legal sign-off: license columns + Elasticsearch gate | Production use of this framework | Legal TBD |
| F-5 | Architecture review: port decomposition + this framework | Framework publication | Arch TBD |
| F-6 | Vespa anchor re-verification (2022) + Milvus 1.8× band | Hardware confidence | TBD |
| F-7 | Storage-footprint batch measurement (10/11 blank) | Capacity planning | TBD |
| F-8 | Week-2 schema freeze sign-off | DOC-D0 final, B5 pre-check formal | Track A+B owners |
| F-9 | Dim-parameterization of PG/YDB POC engines (384d lock-in) | PG/YDB hybrid legs at 768d; cross-engine comparability | TBD |

## 5. Revisit triggers

- Any `unmeasured` cell gets measured → update §1b + affected §2 rows.
- Legal returns a restriction on a universe engine → re-run §2 disqualifications, reopen Gate B0.
- New engine enters the universe → full B0 row before any recommendation cites it.
