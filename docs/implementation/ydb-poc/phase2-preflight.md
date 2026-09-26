# Phase 2 pre-flight — eligibility gate first (YDB-POC-024B)

**Status:** Specified (executes when 010 opens 024B)
**Rule:** pre-ranking eligibility is a **binary gate**, not a benchmark row. No
benchmark, scale, or freshness run in Phase 2 may start until it passes.

## Gate 0: eligibility correctness before anything else

First test on the YDB adapter, before benchmarks: a query with a **highly
selective eligibility predicate** against the YDB vector index — correctness
first, latency second. Three structurally different outcomes, decided up front:

1. YDB composes the eligibility predicate into candidate generation at plan
   level → gate passes, proceed to benchmarks.
2. YDB pushes the filter after ANN retrieval → adapter-side pre-ranking
   filtering is acceptable **iff** the ranked set derives only from eligible
   candidates (prove it; leakage = fail).
3. Eligible-ID materialization + intersect → acceptable only with the changed
   performance profile recorded; thresholds re-examined, not inherited.

If none holds, 024B fails a Must requirement and the Synquest PoC collapses
regardless of latency. That is the correct outcome — do not benchmark past it.

## Capability unknowns (validate explicitly, not mid-benchmark)

- Vector ANN under high-selectivity filters (0.1% leg): degradation mode?
- Full-text ranking vs frozen Lucene BM25 defaults (k1=1.2, b=0.75,
  StandardAnalyzer): tokenization/scoring divergence and Recall@10 impact?
- Hybrid fusion semantics vs frozen RRF (20/100/100/60): same variant, or
  re-baseline the 9.3ms threshold?
- Cursor stability under shard rebalancing (invariant 28)?
- Revision atomicity cost under concurrent contention?
- Vector index build time at 160k chunks (Phase 5 feasibility input)?
- Ordering-key + generation rejection without per-projection read-before-write?

Each gets a pass/fail entry in the Phase-2 report. Unknowns, not defects —
until proven otherwise.
