# Retrieval benchmark results — BM25 vs Dense vs RRF

**Status:** Results
**Last reviewed:** 2026-11-08
**Harness:** `tools/retrieval-eval` (gold query set + `synquest` `/search`)
**Code under test:** the decoupled engine (`SearchEngineAdapter` → `engine.lucene`), `main` @ `108392f`.

## Dataset correction (important)

The brief referred to a **735-query** evaluation. `735` is the **judgment-pool / calibration row count** (`docs/implementation/demo/corpus-tracks.md`, `calibration-batch-v2-manifest.json`, `judgments-pool-v2-manifest.json`) — not a query set. The graded retrieval gold is the `queries-v2.rb-fixed-g5` file: **25 graded queries** were used for the recorded run; the current file carries **27** (2 sanitizer queries added later, with no recorded prior run).

The corpus is the 16-document `demo-data/documents` set. The original `rb-fixed-g5` tenant is no longer present in the benchmark Cassandra; the surviving `demo-fixed` tenant is the same 16 documents under the fixed-window chunker (`bge-small`, 384d, 82 chunks). The gold refs were remapped from `rb-fixed-g5` to `demo-fixed` by `(source_sha256, chunk_ordinal)`.

## Results

### A. Recorded run — 25 graded queries, bge-base 768, 81 chunks (authoritative)

`demo-data/eval/retrieval-benchmark/results/T0{1,2,3}-q25.yaml` (validity `true`, 81/81 vectors).

| Arm | nDCG@10 | MRR@10 | Recall@10 | p50 | p95 |
|---|---|---|---|---|---|
| BM25 only | 0.8240 | 0.7857 | **0.9600** | 6.6 ms | 11.8 ms |
| Dense only | 0.8050 | 0.7933 | 0.8933 | 7.7 ms | 10.2 ms |
| **RRF hybrid** | **0.8588** | **0.8250** | **0.9600** | 80.3 ms | 93.4 ms |

**Finding:** with `bge-base` (768d) and the original 25-query gold, **RRF wins nDCG (+0.035 over BM25) and MRR (+0.039)**, and ties BM25 on Recall@10. Dense alone is the weakest on recall.

### B. Fresh decoupled-engine run — 27 queries, bge-small 384, `demo-fixed` (82 chunks)

`results/T0{1,2,3}-q25-decoupled.yaml` (validity `true`, 82/82 vectors, same query text, gold remapped).

| Arm | nDCG@10 | MRR@10 | Recall@10 | p50 | p95 |
|---|---|---|---|---|---|
| **BM25 only** | **0.7323** | **0.7143** | **0.8333** | 9.4 ms | 24.5 ms |
| Dense only | 0.6380 | 0.6157 | 0.7222 | 27.6 ms | 45.5 ms |
| RRF hybrid | 0.7027 | 0.6821 | 0.7963 | 31.1 ms | 43.6 ms |

**Finding:** on the smaller model and the 27-query/remapped-fixed corpus, **BM25 wins**. RRF improves over Dense (+0.065 nDCG) but does **not** recover the BM25 top ranks.

## Interpretation — do not fudge

The two runs disagree, and both are valid. The difference is not noise-ridden randomness; it tracks the two changed variables:

1. **Embedding quality.** `bge-base` (768d) gives the dense leg enough precision that fusion adds signal over BM25. With `bge-small` (384d), the dense leg contributes weaker candidates; RRF's equal-weight fusion dilutes strong BM25 hits, so hybrid lands between the two and BM25 remains best.
2. **Query/gold composition.** The 27-query file adds two `sanitizer` queries with 0 recall for every arm, plus fixed-window chunks whose `#0` does not always contain the flat-chunk answer the gold was annotated for. This depresses all arms and is not a like-for-like replay of the 25-query flat benchmark.
3. **Dense alone is never the best arm in either run** — its recall is the lowest, which is the strongest argument for fusion over a dense-only engine.

**Bottom line for the roadmap:** the RRF claim is **model- and distribution-dependent**, not universal. The evidence supports "BM25 + dense fusion beats either leg on the strong `bge-base` model and the original gold"; it does **not** support "hybrid always beats BM25" on the small model / fixed-chunk arm. The demo uses `bge-base` (TEI `synanton-bge-base-embedding`), where the recorded evidence favors RRF.

## Threats to validity

- Run B is a different **chunking arm** (fixed-window) and **embedding model** (bge-small 384) from Run A (flat, bge-base 768); do not compare absolute numbers across the two tables.
- Run B's gold was remapped by source SHA + ordinal; `demo-fixed` has no section paths, so section-level anchoring was not available.
- The 2 sanitizer queries contribute zero to all arms; excluding them would raise every mean without changing the arm ordering in either run.
- Latency is client-side end-to-end; Run A's hybrid latency includes GPU-plane query embedding; Run B's includes the local TEI round trip. Not comparable across planes.

## Reproducing

Run A is reproducible from the recorded artifacts (`results/T0*-q25.yaml`; `.hits.json`). To re-run the decoupled engine:

```bash
# 1. benchmark stack (host compose; Cassandra/MinIO/synquest)
#    synquest from current source, small TEI on host:30801
docker compose -f deployments/docker/compose.yaml build synquest
#    ONTOLOGY_V2_PATH=/app/data/ontology-v2.jsonl
#    SYNQUEST_PROJECTION_ENABLED=false
#    EMBED_MODEL=synanton-bge-small-embedding EMBED_DIM=384 SYNQUEST_EMBEDDING_REQUIRED=true
#    VLLM_EMBED_BASE_URL=http://host.docker.internal:30801/v1

# 2. TEI-small (k8s gpu-plane) on the host port the compose expects
kubectl -n gpu-plane scale deployment/tei-embedding-small --replicas=1
kubectl -n gpu-plane port-forward --address 0.0.0.0 svc/tei-embedding-small 30801:8000

# 3. mint a tenant token for the secured /reindex//index/stats/search endpoints
#    RETRIEVAL_EVAL_TOKEN=<HS256 token for tenant demo-fixed, SYNANTON_JWT_SECRET of the compose stack>

# 4. build the index and run the three arms
curl -X POST -H "Authorization: Bearer $RETRIEVAL_EVAL_TOKEN" \
  'http://localhost:8083/reindex?tenant=demo-fixed'
retrieval-eval evaluate --tenant demo-fixed \
  --queries demo-data/eval/retrieval-benchmark/queries-v2.demo-fixed.jsonl \
  --run-id T01-q25-decoupled --search-config bm25-only --embedding-model none \
  --retrieval-strategy bm25 --top-k-dense 0 --top-k-lexical 100
retrieval-eval evaluate --tenant demo-fixed ... --search-config dense-only \
  --embedding-model synanton-bge-small-embedding --retrieval-strategy dense \
  --top-k-dense 100 --top-k-lexical 0
retrieval-eval evaluate --tenant demo-fixed ... --search-config hybrid-rrf \
  --embedding-model synanton-bge-small-embedding --retrieval-strategy hybrid
```

The harness now sends `RETRIEVAL_EVAL_TOKEN` as a Bearer token (`query.py`/`ingest.py`) because the search-plane security filter is enforced on the legacy `/search`, `/reindex`, and `/index/stats` endpoints.

## Artifacts

- `results/T01-q25.yaml`, `results/T02-q25.yaml`, `results/T03-q25.yaml`, `results/T04-q25.yaml` — Run A.
- `results/T01-q25-decoupled.yaml`, `T02-q25-decoupled.yaml`, `T03-q25-decoupled.yaml` — Run B.
- `queries-v2.demo-fixed.jsonl` — Run B gold (remapped refs).
