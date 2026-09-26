# YDB-POC-006 — Baseline Measurement Protocol and Thresholds

**Status:** Closed as a unit with 004 (protocol + rules frozen; absolutes computed from the table below)
**Date:** 2026-09-26
**Baseline:** ingestion-cache-backed Lucene path (004 correction — not "Cassandra search")

## Measurement protocol (frozen)

- Harness: `BaselineBench` in `java/synquest` test sources, gated by
  `-Dydb.bench=true` (never runs in PR CI). Builds a seeded (seed 42) synthetic
  index with the **production schema/analyzer/similarity** (`StandardAnalyzer`,
  `BM25Similarity` defaults, `KnnFloatVectorField` COSINE) and drives the
  **production** `HybridSearcher` + `RrfFusion` exactly as `SearchService` does
  (lexical/dense top 100, RRF k=60, topK 20).
- Lexical recall is real (planted relevance, 40 golden queries × 5 relevant).
  Vector-leg numbers are **latency only** (synthetic 384-d vectors carry no relevance).
- Reporting: `BENCH` line in test stdout; warm-up 1 rep, 5 measured reps per query.

## Preliminary operating points (superseded — see absolutes below)

16k-chunk run (same harness, defaults):
`BENCH lex_ms_p50=0 lex_ms_p95=0 vec_ms_p50=3 vec_ms_p95=5 hyb_ms_p50=0 hyb_ms_p95=0 lex_recall10=1.000 chunks=16000`
(ms resolution; kept for scale-trend reference only.)

## Measured absolutes — v1-corpus scale (160k chunks, this machine, 2026-09-26)

`BENCH lex_ms_p50=0.196 lex_ms_p95=0.594 vec_ms_p50=5.349 vec_ms_p95=7.864 hyb_ms_p50=5.656 hyb_ms_p95=7.749 lex_recall10=1.000 chunks=160000`

Run: `BaselineBench` with `-Dydb.bench.docs=20000 -Dydb.bench.chunks=8`, seed 42,
40 golden queries × 5 planted relevant, production `HybridSearcher` + `RrfFusion`
(lexical/dense top 100, RRF k=60, topK 20). Hybrid is timed as one unit over
concurrent legs + fusion (mirrors `SearchService`); vector leg latency-only
(synthetic 384-d vectors carry no relevance); vector **recall** absolute still needs
the real embedding pipeline — gated absolute below.

**Correction history:** the first v1 run timed only `RrfFusion.combine`
(hybrid p95 0.33ms — faster than its inputs, impossible for the real pipeline).
Re-measured with the full concurrent pipeline; the combine-only number is void
and must not be cited. Lesson recorded: hybrid timing boundary = legs + fusion.

## Frozen thresholds (absolute)

| Metric | Baseline | Threshold (rule applied) |
|---|---|---|
| p95 lexical latency | 0.594 ms | ≤ **0.72 ms** (×1.20) |
| p95 vector latency | 7.864 ms | ≤ **9.5 ms** (×1.20) |
| p95 hybrid latency (concurrent legs + fusion) | 7.749 ms | ≤ **9.3 ms** (×1.20) |
| Lexical Recall@10 | 1.000 | ≥ **0.98** (−0.02) |
| Vector Recall@10 | unmeasured (no embedding pipeline) | ≥ **0.95 absolute** (Option B below — not baseline-relative) |
| Index freshness | pending relay (029) | ≤ baseline × 1.20 once measured |
| Error rate | 0 observed | ≤ baseline under equivalent load |

## Sign-off

Numbers above are measured and committed. Formal sign-off (Search Eng + Platform)
is an exit-review checklist line. The rules are frozen — sign-off changes no
threshold, only records agreement.
