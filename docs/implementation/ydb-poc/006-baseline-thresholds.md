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

## Preliminary operating points (this machine, 16k chunks)

`BENCH lex_ms_p50=0 lex_ms_p95=0 vec_ms_p50=3 vec_ms_p95=5 hyb_ms_p50=0 hyb_ms_p95=0 lex_recall10=1.000 chunks=16000`

- Lexical/hybrid sub-millisecond at this scale (ms resolution; re-measure at
  v1-corpus scale with µs reporting).
- Lexical Recall@10 = 1.000 on planted relevance (sanity: the path retrieves
  what it indexes).

## Frozen threshold rules (absolute computation)

With `B_*` = agreed baseline row (preliminary row above until the v1-corpus run):

- p95 lexical/vector/hybrid latency ≤ `B_p95 × 1.20`
- Recall@10 ≥ `B_recall − 0.02`
- Index freshness (commit → search-visible) ≤ `B_fresh × 1.20`
- Error rate ≤ baseline under equivalent load

## Agreed-absolute gate (single remaining item, tracked here)

Absolute sign-off (Search Eng + Platform) happens on the **v1-corpus run**
(160k chunks, corpus spec in `005-corpus-definition.md`), which needs the corpus
generator + embedding pipeline (Phase 0D/Phase 1 entry work). Until then the
preliminary row above is the comparison baseline and the rules above are frozen —
no second matrix edit is needed when absolutes land, only the numbers row.
