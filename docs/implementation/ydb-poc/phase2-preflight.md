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

## Collapse scope: Gate 0 fails 024B, not Phase 2

- **024B (YdbSynquestEngine)** — collapses. Correct.
- **028 as a YDB comparison** — collapses with it. Correct.
- **024A (Cassandra port over ingestion-cache/Lucene)** — unaffected. It does not
  depend on Gate 0; it runs to completion and still produces a baseline-vs-024A
  comparison.
- **Phase 6 Outcome 5** ("dedicated search/vector backend remains necessary") —
  becomes the reachable outcome. Gate 0 failure is a decision with a named
  outcome, not a stop: YDB's search path fails a Must, the adapter path still
  gets evaluated, Phase 6 selects from Outcomes 2–5.

## Capability unknowns (validate explicitly, not mid-benchmark)

Pass/fail rows — each gets a binary entry in the Phase-2 report:

- Vector ANN under high-selectivity filters (0.1% leg): degradation mode?
- Full-text ranking vs frozen Lucene BM25 defaults (k1=1.2, b=0.75,
  StandardAnalyzer): tokenization/scoring divergence and Recall@10 impact?
- Hybrid fusion semantics vs frozen RRF (20/100/100/60): same variant, or
  re-baseline the 9.3ms threshold?
- Cursor stability under shard rebalancing (invariant 28)?
- Revision atomicity cost under concurrent contention?
- Vector index build time at 160k chunks (Phase 5 feasibility input)?
- Ordering-key + generation rejection without per-projection read-before-write?

Annotation rows — risk notes attached to the pass/fail rows they qualify, never
gates themselves:

- Preview/beta status of any YDB feature a pass/fail row depends on (from the
  003 stability inventory). A preview-flagged dependency records production
  risk for Phase 6 to weigh; it neither fails nor passes the row.

Unknowns, not defects — until proven otherwise.
