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

### 1b. Functional columns (measured F-2/F-3 legs; everything else flagged)

Recall@10 on the 12-query human gold set (`queries-f1-dense.json`, single
annotator, demo corpus). Two embedding columns: bge-base 768d vs bge-small
384d — model and engine vary together across columns, so cross-model engine
claims are flagged, not made.

| Engine | Recall bge-base 768d | Recall bge-small 384d | Latency (measured) | Status |
|---|---|---|---|---|
| Lucene-hybrid (service) | 0.861 | 0.847 | p50/p95 tens of ms | measured, n=12 |
| Lucene-vector (service) | — | 0.889 | same path | measured, n=12 |
| Lucene-standalone | 0.778 | 0.806 | p50 2.7 / p95 31 ms (toy) | measured, n=12 |
| Milvus (HNSW M16) | 0.847 | 0.847 | p50 3.5 / p95 50.5 ms (toy) + leg timings | measured, n=12 |
| Qdrant (HNSW) | 0.847 | 0.847 | p50 2.2 / p95 27.1 ms (toy) + leg timings | measured, n=12 |
| PG+pgvector | — (384d lock-in) | 1.0 | p95 21.6 ms (lexical legs) | measured, n=12 |
| YDB-hybrid | — (384d lock-in) | 1.0 | p50/p95 19.8/40.8 ms (lexical legs) | measured, n=12 |
| Cassandra+Lucene | `unmeasured` (BM25 latencies p50 4.5/p95 36 ms) | — | BM25-only | partial |
| Tantivy / Quickwit / Weaviate / Vespa / OpenSearch | `unmeasured` | `unmeasured` | per B0.3 | unmeasured |

Ranking by mean: PG=YDB (1.0) > Lucene-vector-384 (0.889) >
Lucene-hybrid-768 (0.861) > Milvus=Qdrant both models + Lucene-hybrid-384
(0.847) > Lucene-standalone-384 (0.806) > Lucene-standalone-768 (0.778).
Milvus=Qdrant are byte-identical per query on both models. All cells n=12.

Latency at bench scale carries JVM/GC/RPC noise; treat p95 gaps under ~2×
as ties. Overhead models (Milvus ~1.8×, Qdrant fixed/node) per B0.3.

### 1c. What the functional evidence does and doesn't support

- Supports: quality ranking PG=YDB > rest at 384d/small (n=12, human gold);
  model effect on Lucene (+0.111 small over base at n=3, confirmed direction
  at n=12); Milvus=Qdrant equivalence (identical rankings, both models);
  integration effect zero (B5.5: service-vector-only == standalone adapter,
  byte-identical); Lucene parity with Cassandra+Lucene (R3 resolved).
- Does NOT support: cross-model engine claims for PG/YDB (no 768d cells —
  structural lock-in); any scale claim; metadata-store effects (B5.4 null by
  construction); blind-fusion confidence (F-tune: RRF buries single-leg hits,
  k-inert, depth-exonerated).

---

## 2. Per-context recommendations (with functional evidence)

| Context | Recommended | Alternative | Functional note |
|---|---|---|---|
| Cloud-managed | PostgreSQL + pgvector | Qdrant Cloud | PG hybrid recall 1.0 (n=12, 384d) — strongest quality cell; HNSW figure unpublished so capacity planning carries uncertainty |
| Cloud-self-hosted | Qdrant | PG + pgvector | Qdrant 0.847 both models (n=12); simplest ops in class; PG alternative pending 768d cells |
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
| F-9a (resolved 2026-10-11) | bge-small 384d legs instead of engine surgery: PG 1.0, YDB-hybrid 1.0, full model×engine matrix in tracker F-9 | Closed the leg gap with model confound flagged per-cell | Done |

## 5. Revisit triggers

- Any `unmeasured` cell gets measured → update §1b + affected §2 rows.
- Legal returns a restriction on a universe engine → re-run §2 disqualifications, reopen Gate B0.
- New engine enters the universe → full B0 row before any recommendation cites it.
