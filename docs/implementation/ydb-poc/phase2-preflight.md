# Phase 2 pre-flight — eligibility gate first (YDB-POC-024B)

**Status:** Specified (executes when 010 opens 024B)
**Rule:** pre-ranking eligibility is a **binary gate**, not a benchmark row. No
benchmark, scale, or freshness run in Phase 2 may start until it passes.

## Gate 0: eligibility correctness before anything else

**Verdict 2026-09-26: PASS on both legs (live YDB 26.3 local build), hybrid
flag resolved go (cluster-level `table_service_config.enable_hybrid_search`,
proven on the PoC container with tenant-scoped `HybridRank`).**
Filtered vector index `ON (tenant, embedding)` + mandatory tenant equality
returns only eligible rows, correctly ranked; unfiltered `fulltext_relevance`
+ tenant equality + alias-form `FulltextScore` likewise. 024B may proceed to
engine work and benchmarks. Standing caveats below remain in force for 024B.
Filtered vector index `ON (tenant, embedding)` + mandatory tenant equality
returns only eligible rows, correctly ranked; unfiltered `fulltext_relevance`
+ tenant equality + alias-form `FulltextScore` likewise. 024B may proceed to
engine work and benchmarks. Standing caveats below remain in force for 024B.

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

## 028 pre-flight: 024A convergence check
024A mirrors the baseline's field schema; it does not reuse its code
(`LuceneIndexBuilder` is a Spring `@Component` in a service module whose plain
jar is disabled — service modules are not libraries, so no clean reuse exists;
Phase 5 must either mirror or extract a shared core). Before any three-legged
comparison: run 024A against the frozen corpus and require results within
tolerance of the baseline legs (same metric shape). **Verify the comparator
before comparing against it.** If convergence fails, 028 needs a fourth leg or
the three-legged framing is revised — Phase 6 must never have to distinguish
"adapter overhead" from "mirror drift" after the fact.

Known 024A divergences (convergent core, divergent edges — the check quantifies):
convergent: same index tech, `StandardAnalyzer`, BM25 defaults, KNN cosine,
RRF-60 formula, tenant-dir isolation. Divergent: metadata stored as `meta_*`
fields vs the service's individual stored fields; hybrid scores normalized by
max vs raw RRF; no section-expansion/rerank paths; fixed top-100 legs (service
allows per-request overrides).

## 028 pre-flight caveats (Gate 0 probe findings)
- **Empty-table degradation:** YDB vector indexes built on empty tables degrade
  to scans. The benchmark corpus must be fully built **before** indexes, in the
  harness and in 024B setup — assert index usage in results, not just latency.
- **Filtered-fallback latency:** selective-predicate lexical legs run on the
  unfiltered-FT + equality fallback (prefixed FT is flag-disabled on this
  build), not on a native filtered index. If fallback latency differs from
  native, 028's lexical numbers measure the fallback — note it per-leg in
  results rather than silently comparing against native-filtered expectations.
- **Hybrid runs flagged:** the 028 hybrid leg executes with
  `enable_hybrid_search=true` set (non-default). Results carry that annotation
  for production-readiness review. Hybrid numbers are PoC-valid,
  production-pending: 26.3 RC semantics may change on GA, so Phase 6 must not
  read hybrid latency as a production-ready figure.
- **Synthetic vectors: latency measurable, recall unclaimed.** 024B runs on
  synthetic 384-d vectors per the corpus spec; the baseline runs whatever dim
  its embedder produced (service default 768). Vector-leg latency compares only
  at matched dims — record both dims with every number. Recall on synthetic
  vectors is meaningless: 028's vector leg produces a latency number for 024B
  and **no recall number**; the ≥0.95 gate waits on the real pipeline. A report
  showing "vector leg run" without this note will be misread as recall tested.
- **Dimension bias (latency itself):** ANN cost scales with dimension, so a  384-d 024B leg is intrinsically cheaper than a 768-d baseline leg. Either
  match dims (re-index one side) or annotate every vector-leg number with both
  dims and the direction of bias. Phase 6 must not over-read the gap.
- **minScore semantics differ per leg:** baseline minScore lives in BM25 score
  space; 024B hybrid minScore lives in rank-score space (1/(60+position)).
  The same numeric value filters differently on each side. 028 runs with
  **minScore=0 on all legs** (neutral filter); any non-zero cutoff is
  normalized per-leg in the harness and recorded as such. Never compare
  "baseline at minScore=X" with "YDB at minScore=X" as if X meant one thing.
- **024A convergence gate (blocking for 028):** before the three-legged run,
  024A and the baseline service run the frozen corpus and top-K results must
  agree within tolerance (same metric shape). If convergent, 028 is valid; if
  not, the frame is revised (fourth leg or re-scope) — adapter overhead vs
  mirror drift must never be disentangled after the fact.

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
