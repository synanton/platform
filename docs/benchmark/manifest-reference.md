# Benchmark Manifest reference (DOC-D0.1)

**Schema:** `schemas/benchmark/benchmark-manifest.schema.json` (`0.2.0-draft`, pre-freeze).
**Goal:** author a valid manifest from this doc alone.
**Validate:** `benchmark-runner validate <manifest.json>` (exit 0/2).

---

## Top-level fields

| Field | Required | Type | Meaning |
|---|---|---|---|
| `manifest_id` | yes | string | Stable human-readable run identifier, e.g. `vec-6comp-r1`. |
| `schema_version` | yes | string | `MAJOR.MINOR.PATCH` with optional `-draft`. Drafts are mutable; frozen schemas are immutable. |
| `corpus` | yes | object | Corpus reference (below). |
| `compositions` | yes | array ≥1 | The N compositions to execute (below). |
| `metrics` | yes | object | Requested metrics (below). |
| `reproducibility` | yes | object | Seed + hash verification (below). |
| `cost_controls` | yes | object | Guards; all optional inside, all fail loud (below). |
| `output_sink` | yes | object | Where artifacts go (below). |

## `corpus`

| Field | Required | Type | Meaning |
|---|---|---|---|
| `ref` | yes | string | `cas:<64-hex>`. The hash IS the identity; runner aborts on mismatch. |
| `alias` | yes | string | Human alias, e.g. `demo-frozen-v1`. |
| `dataset_version` | yes | string | Track B-published pinned version. |
| `registry` | no | string | Path/URI of the corpus registry resolving the alias. |

## `compositions[]`

| Field | Required | Type | Meaning |
|---|---|---|---|
| `composition_id` | yes | string | Runner slug: `metadata.provider + vector.provider + index-params-hash`. |
| `metadata_provider` | yes | enum | `cassandra` \| `postgres` \| `ydb` \| `lucene` (VEC-B2.1 namespace). |
| `vector_provider` | yes | enum | `lucene` \| `milvus` \| `qdrant` \| `pgvector` \| `ydb`. Explicit here; config-level default-to-metadata lives in deployment config. |
| `endpoint_refs` | no | object | `{"VAR_NAME": "${VAR_NAME}"}` — by-name refs only, never inline secrets. Keys `^[A-Z][A-Z0-9_]*$` (see `.env.default`). |
| `index_params` | no | object | Engine params (`m`, `ef_construction`, …; engine-specific keys allowed). Hashed into `composition_id`. |
| `embedding` | yes | object | `model_id` (string) + `dim` (1–4096). |
| `execution` | yes | object | `default`: `parallel` \| `sequential`; `per_query_override`: bool. |

## `metrics`

Mandatory (must be present and true/`[10]`-valued for B5): `recall_at_k` (include 10),
`eligible_set_identity`, `latency_percentiles` (include 95; per-leg breakdown always
collected), `reproducibility_flags`, `topology_per_query` (true — no metric without
its topology). Optional: `ndcg_at_k`, `mrr_at_k`, `index_build_time`,
`index_size`, `freshness`, `rag_quality` (null until owned — deferred per BR-A0.1).

## `reproducibility`

| Field | Required | Type | Meaning |
|---|---|---|---|
| `seed` | yes | integer | Query-sampling seed. No seed → no reproducibility claim (run aborts). |
| `verify_corpus_hash` | yes | bool | True: abort before execution on mismatch. |
| `environment_record` | no | enum | `lightweight` (versions + model ids/dims) or `full-digests` (Phase-5). |

## `cost_controls` (all optional, all loud)

`dry_run` (validate only, no artifacts), `timeout_ms` (≥1, aborts the run),
`max_event_count` (≥1, fires past limit), `approval_threshold_usd` (≥0, Track A
approval above estimate).

## `output_sink`

`file` required (`base_path` + `atomic_write`); `kafka` (`topic`) and
`clickhouse` (`table`) optional. Artifact exists ⟺ run completed successfully.

## Minimal example

```json
{
  "manifest_id": "smoke-1comp",
  "schema_version": "0.2.0-draft",
  "corpus": {"ref": "cas:<64-hex>", "alias": "demo-frozen-v1", "dataset_version": "demo-frozen-v1"},
  "compositions": [
    {"composition_id": "cassandra-lucene-m16", "metadata_provider": "cassandra",
     "vector_provider": "lucene", "embedding": {"model_id": "bge-base", "dim": 768},
     "execution": {"default": "parallel"}}
  ],
  "metrics": {"recall_at_k": [10], "eligible_set_identity": true,
    "latency_percentiles": [50, 95, 99], "reproducibility_flags": true,
    "topology_per_query": true},
  "reproducibility": {"seed": 7, "verify_corpus_hash": true},
  "cost_controls": {"dry_run": true},
  "output_sink": {"file": {"base_path": "runs/smoke", "atomic_write": true}}
}
```

Full 6-composition example: `schemas/benchmark/fixtures/six-composition.json`.
