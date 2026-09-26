# Version-coupled assumptions (single list — re-validate ALL on YDB change)

One trigger, one list. Scattered entries were consolidated here 2026-09-26;
other docs point here instead of duplicating.

| # | Assumption | Observed on | Re-validate |
|---|---|---|---|
| 1 | OCC conflict = ABORTED **400040** (`YdbSynvaultStore.map`) | 26.3.1 local | Error code + abort semantics |
| 2 | `HybridRank` RC disabled-by-default; flag `table_service_config.enable_hybrid_search` | 26.3 RC | Ranking, fusion params, eligibility composition |
| 3 | FT on composite-PK tables needs `enable_fulltext_index_row_id` + `enable_add_unique_index` + `enable_online_add_unique_index` | 26.3.1 local | Flag names + row-id machinery |
| 4 | Filtered (prefixed) FT needs `enable_fulltext_index_prefix` | 26.3.1 local | Flag name + fallback validity |
| 5 | Vector syntax: `vector_kmeans_tree`, `Untag(ToBinaryStringFloat)`, `String`≠`Utf8`, `VIEW`-mandatory, alias-form score | 26.3.1 local | DDL + UDF shapes |
| 8 | HybridRank constraints: single-column PK only; multi-index branches need `("a","b") AS Indexes`; rank unprojectable (rank-derived RRF-60 scores) | 26.3.1 local | Any one may lift on GA — re-probe before trusting |
| 6 | Table prefixes are PoC-scoping, not production convention | n/a | Phase-5 migration plan |
| 7 | Pinned image `local-ydb:stable-26-3-1-path-aliases` ≠ pinned server `26.3.1.16` | 001 | Exact server build before production-track claims |

Executable companion: `ydb-poc-cluster-flags.yaml` (the four flags as config).
Startup check: `YdbSearchSchema` maps index-creation failures to flag names
(fail fast with the capability, not a raw server error).
