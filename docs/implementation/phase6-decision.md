# Phase 6 Decision Input — Four-Legged R3 Record

**Verdict: R3-CONDITIONAL-PASS (four legs).** The conditional prefix is
load-bearing — this is not PASS. Source: `r3-verdict-final.json` (+
`runs/*-v1-convergence.md` per-leg reports).

## Hard gates (binary — all pass on all four legs)

Eligible-set identity 1.00, no leakage, lexical parity 1.00 — baseline,
Cassandra, YDB, PG. Gates answer "is it correct"; the matrix below
answers "how close". Do not rank on gates.

## Candidate matrix (continuous scores)

| Axis | Cassandra | YDB | PG | Baseline |
|---|---|---|---|---|
| Lexical overlap | 1.00 | 1.00 | 1.00 | — |
| Vector overlap | 0.88 | 0.83 | 0.77 | — |
| Hybrid overlap | 0.48 | 0.71 | 0.67 | — |
| Load rate | in-JVM build (~50 min, different op) | ~770 rows/s bulk RPC | ~218 rows/s JDBC batches | — |
| Production posture | library defaults, no build.json (predates convention — gap stated) | 041-parameterized, build.json | strict_order/probes=10, build.json `ann_session` | — |

Cassandra leads on vector (0.88) with no dedicated tuning — defaults
already top the cluster. YDB (0.83) and PG (0.77) both required
intervention (parameterization; session tuning) to reach their numbers.
The operational cost of reaching a number is a different axis than the
number itself. YDB leads hybrid (0.71); PG (0.67) is statistically
indistinguishable from YDB given per-cell n — the 0.04 gap sits inside
the measurement noise floor. Both materially outperform Cassandra (0.48).

Workload qualifier (table-level, not PG-specific): aggregates are PoC
summary statistics. PG's per-selectivity cut reads 0.14–0.59 at
operational selectivity (directional, n=2–3); YDB/Cassandra
per-selectivity cuts are not yet available, so their aggregates carry
the same unmeasured caveat. An operational workload dominated by
high-selectivity queries should not read any column at face value.

## Divergence table

| Leg | Backend | Number | Mechanism | Status |
|---|---|---|---|---|
| Vector | YDB | 0.83 | Synthetic 384-d — unmeasurable recall | Pre-declared, closed |
| Vector | PG | 0.77 | Approximate-then-filter residual (was 0.23: probes=1 + overfiltering, both addressed via session tuning) | Tuned, bounded |
| Vector | Cassandra | 0.88 | None observed | Closed |
| Hybrid | YDB | 0.71 | Pre-declared fusion semantics | Closed (tied with PG within noise) |
| Hybrid | PG | 0.67 | Follows vector residual through RRF | Tied with YDB within noise |
| Hybrid | Cassandra | 0.48 | RRF semantics vs production HybridSearcher — intentional-difference or mirror-drift, unresolved | Deferred to YDB-track with both candidates named |

## Open for the decision meeting

- Outcome 1–9 selection (Outcome 8 weakened: PG's gap closed by session
  tuning alone, no architecture split required).
- PG-tuned-vs-default framing: resolved to tuned (column declaration in
  R3 record; default preserved as diagnostic).
- Cassandra tension (intentional vs drift).
- Missing sections for a complete input: query latency (p95/p99), cost
  per row/query, operational complexity, migration path. Those land as
  later sections; the matrix alone isn't the decision.
