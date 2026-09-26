# Benchmark execution plan — three-legged comparison (022 / 028 / 032)

**Status:** Specified (execution gated on 010 via 021/024A/024B — this doc is the
spec, not authorization to start implementation)
**Date:** 2026-09-26
**Inputs:** `004-parity-matrix.md` (frozen), `006-baseline-thresholds.md` (frozen),
corpus `ydb-poc-corpus-v1` (005)

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

## Failure modes (explicitly excluded)

- Non-transactional Cassandra writes vs YDB atomic revisions, unannotated.
- "Cassandra search" baseline label.
- YDB search legs without eligibility filtering.
- The void 0.33ms hybrid number or combine-only timing boundary.
- Single-ratio reporting.
