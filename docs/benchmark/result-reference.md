# Result Manifest reference (DOC-D0.2)

**Schema:** `schemas/benchmark/result-manifest.schema.json` (`0.2.0-draft`, pre-freeze).
**Goal:** interpret a Result Manifest without reading the schema.
**Note:** R3 artifacts (`runs/*-v1.json`) predate runner fields and validate as-is;
new fields are optional precisely for that reason.

---

## Top-level fields

| Field | Required | Type | Meaning |
|---|---|---|---|
| `run_id` | yes | string | `{manifest_id}-{composition_id}` on runner runs; legacy IDs on R3 artifacts. |
| `corpus` | yes | string | Corpus identifier as recorded (plain string on R3; alias of `cas:` ref on runner runs). |
| `queries` | yes | array | Per-query results (below). |
| `manifest_id` | no | string | Producing manifest (BR-A0.2). Absent on R3. |
| `composition_id` | no | string | Evaluated composition slug. Absent on R3. |
| `metrics_summary` | no | object | Run-level rollups (below). Absent on R3. |
| `reproducibility` | no | object | `verified` bool, `corpus_hash`, `seed`. Absent on R3. |
| `environment` | no | object | `runner_version`, `embedding_model`, `embedding_dim` + open extras. Absent on R3. |

## `queries[]`

Required per query: `top_k` (array of `{chunk_id, rank ≥ 0, score}`),
`eligible_set` (array of chunk-ID strings), `timing_ms` (≥ 0),
`timing_scope` (string, e.g. `summed_fanout_50`, `simulated`).
Carried through from R3 where present: `query_id`, `mode`, `filter`,
`selectivity`, `structural_empty` (excluded from percentile math — no signal,
not zero signal), `min_score`.
New: `topology` (string, e.g. `btree_sort`, `milvus_gpu`) — required on
runner-produced artifacts, absent on R3. No metric without its topology.

## `metrics_summary` (all fields nullable pre-freeze — null means unmeasured)

`recall_at_k` (`{"10": 0.9}`), `overlap`, `eligible_identity_rate`,
`latency_p50_ms` / `latency_p95_ms` / `latency_p99_ms`, `index_build_time_s`,
`index_size_bytes`, plus open extras (schema allows additional properties).
Definitions: `metric-taxonomy.md` (same directory).

## Reading an R3 artifact vs a runner artifact

- R3 (`runs/baseline-v1.json` etc.): top-level `run_id`/`corpus`/`queries` only;
  120 queries each; `structural_empty` legs present on some files.
- Runner: adds `manifest_id`, `composition_id`, per-query `topology`,
  `metrics_summary`, `reproducibility`, `environment`.

## Minimal example

```json
{
  "run_id": "vec-6comp-r1-cassandra-lucene-m16",
  "corpus": "demo-frozen-v1",
  "manifest_id": "vec-6comp-r1",
  "composition_id": "cassandra-lucene-m16",
  "queries": [
    {"query_id": "q1",
     "top_k": [{"chunk_id": "c1", "rank": 0, "score": 1.0}],
     "eligible_set": ["c1", "c2"], "timing_ms": 12.5,
     "timing_scope": "simulated", "topology": "lucene_sim"}
  ],
  "metrics_summary": {"recall_at_k": {"10": 1.0}, "latency_p95_ms": 12.5},
  "reproducibility": {"verified": true, "corpus_hash": "cas:…", "seed": 7},
  "environment": {"runner_version": "0.2.0", "embedding_model": "bge-base", "embedding_dim": 768}
}
```
