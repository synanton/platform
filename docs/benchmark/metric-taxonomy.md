# Metric Taxonomy (DOC-D0.3) — canonical definitions

**Schemas:** `schemas/benchmark/benchmark-manifest.schema.json`,
`schemas/benchmark/result-manifest.schema.json` (both `0.2.0-draft`).
**Companion docs:** `manifest-reference.md`, `result-reference.md` (same directory).

**Status:** Draft for Week-2 freeze (ships with BR-A0.2/A0.3 schemas).
**Rule:** Both tracks use these definitions verbatim. "Overlap" means §3 below —
nothing else. Field names match `result-manifest.schema.json` and
`benchmark-manifest.schema.json`.

---

## 1. recall@k

For one query: `|relevant ∩ top_k| / |relevant|`, where `relevant` is the gold
chunk-ID set for that query and `top_k` is the ranked chunk-ID list truncated at k.
Run summary: mean over queries with non-empty gold sets. Queries with empty gold
sets are excluded from the mean (counted, not averaged as zero).

- k = 10 is mandatory for B5 (`recall_at_k: [10]`); further cutoffs optional.
- Reported in `metrics_summary.recall_at_k` keyed by k as strings (`{"10": 0.9}`).

## 2. eligible_set_identity

Boolean per query: the returned `eligible_set` equals the independently computed
expectation for that query's filter/selectivity, as sets (order-insensitive).
Guards filter and eligibility bugs separately from ranking quality: a query can
have perfect recall@k and a failed identity check (right chunks, wrong universe),
or vice versa. Run summary: fraction of queries passing.

## 3. overlap

For one query: `|top_k ∩ eligible_set| / |eligible_set|` — the fraction of the
eligible universe surfaced in the ranked list. Empty eligible set → overlap is
null for that query (not zero: no signal, not zero signal). Run summary: mean
over non-null queries. This is the only sanctioned meaning of "overlap" in
either track.

## 4. Latency percentiles

Per-query `timing_ms`, aggregated as p50/p95/p99. Mandatory: p95.
**Exclusion rule (inherited from the benchmark tracker): legs flagged
`structural_empty` are EXCLUDED from percentile math** — no signal, not zero
signal. Per-leg breakdown (`embedMs`, `denseMs`, `lexicalMs`, `fusionMs` from
`SearchTrace`) is always collected alongside; total wall time is reported
separately and never averaged into leg percentiles.
Reported in `metrics_summary.latency_p50_ms` / `latency_p95_ms` / `latency_p99_ms`.

## 5. Index build time

Wall-clock seconds to build the evaluated index from the frozen corpus
(`metrics_summary.index_build_time_s`). Single figure per composition; includes
embedding fetch only if the build performs it (native-vector caches exclude it —
state which in the run record).

## 6. Index size

Bytes on disk of the built index (`metrics_summary.index_size_bytes`), measured
post-commit, pre-query. Excludes the source corpus and the ingestion cache.

## 7. Freshness

Two-part, because "fresh" conflates two checks:
- **Version match (boolean):** index built from the manifest-pinned `dataset_version`
  (`reproducibility.corpus_hash` equals corpus ref). Mismatch = stale, run invalid.
- **Age (informational):** index build timestamp vs corpus publication timestamp.
  No threshold in B5; recorded for Phase-5 longitudinal use.

## 8. Topology annotation (not a metric — a metric qualifier)

Every per-query metric carries `topology` (e.g. `btree_sort`, `ivfflat`,
`milvus_gpu`). A metric value without its topology is uninterpretable across
compositions and must be rejected at Result Manifest validation for
runner-produced artifacts (R3 artifacts predate the field and are exempt).

## 9. Explicit nulls

Any metric that cannot be computed is recorded as explicit null with the reason
in the run record — never as zero, never omitted silently. Zero is a claim;
null is an admission.
