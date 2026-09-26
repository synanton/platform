# Benchmark execution plan — three-legged comparison (022 / 028 / 032)

**Status:** Specified (execution gated on 010 via 021/024A/024B — this doc is the
spec, not authorization to start implementation)
**Date:** 2026-09-26
**Inputs:** `004-parity-matrix.md` (frozen), `006-baseline-thresholds.md` (frozen),
corpus `ydb-poc-corpus-v1` (005)

**Outcome independence:** this spec survives either 010 outcome. On frozen, results
carry full weight against the frozen thresholds; on throwaway (or expired-silence
activation), the same spec executes and conclusions carry reduced commitment with
no production pre-commit. Nothing here needs rewriting per outcome — only Phase 6
interpretation changes.

## Purpose (recorded to prevent scope drift)

Not "is YDB faster than Cassandra." The question for Phase 6 is whether YDB meets
the architecture's required thresholds **while providing the transactional
guarantees Cassandra cannot**. Any reporting that reduces to a single latency
ratio is a defect in the benchmark, not a result.

## Legs (same corpus, same harness, same selectivity points)

| Dimension | Baseline (Lucene + ingestion-cache) | 024A (Cassandra port) | 024B (YDB) |
|---|---|---|---|
| Lexical search | p50/p95/p99, Recall@10 | same | same |
| Vector search | p50/p95/p99 latency only (recall unmeasured — absolute gate applies) | same | same + Recall@10 vs ≥0.95 absolute |
| Hybrid search | full concurrent pipeline timing (combine-only number void) | same | same |
| Eligibility-filtered search | 0.1/1/10/100% selectivity | same | same |
| Metadata-filtered search | 0.1/1/10/100% selectivity | same | same |
| Write path (metadata only) | throughput, latency | same | same |
| Write path (revision atomicity) | N/A — non-conforming | N/A — non-conforming | measured vs absolute target |
| Index freshness (commit → visible) | if applicable | same | same |

Labels stay literal: search legs compare **Lucene vs YDB**, never "Cassandra
search" (which does not exist). Write legs compare only conforming-equivalent
operations; revision atomicity is YDB-measured-against-absolute.

## Three outputs (not one verdict)

1. **Threshold pass/fail per capability** — binary gates vs frozen absolutes
   (lex 0.72 / vec 9.5 / hybrid 9.3ms; lexical Recall ≥ 0.98; vector Recall ≥ 0.95).
2. **Conformance-adjusted performance** — mutually supported ops only (metadata
   reads/writes, non-transactional search); informs cost/capacity planning.
3. **Capability-cost annotation** — cost of operations only YDB performs (atomic
   revisions): data for whether the architecture's guarantees are worth it.

## Harness reproducibility (frozen with the spec)

Thresholds are absolute, so the harness must be too — two teams running this spec
must produce comparable numbers:

- Container images pinned by digest (same pattern as the `cassandra:4.1` test pin;
  YDB image pinned when 021 lands).
- JVM version and flags pinned and recorded with results (baseline ran OpenJDK 21).
- Warmup: ≥1 full pass over the query set discarded before measurement
  (baseline: 1 warmup + 5 measured reps per query).
- Steady-state: measurement begins after warmup with no outstanding compactions /
  index builds; record the check used.
- Client concurrency fixed and recorded (baseline: single-threaded legs, 2-thread
  pool for hybrid mirroring `SearchService`).
- Machine class (CPU, memory, disk type): TBD at Phase 2 open, recorded with results.

## Threshold-drift rule

If any frozen absolute threshold changes after Phase 2 measurement begins, all
affected measurements must be re-run against the new threshold before use in
Phase 6. Same category as the YDB stability re-validation rule.

## Failure modes (explicitly excluded)

- Non-transactional Cassandra writes vs YDB atomic revisions, unannotated.
- "Cassandra search" baseline label.
- YDB search legs without eligibility filtering.
- The void 0.33ms hybrid number or combine-only timing boundary.
- Single-ratio reporting.
