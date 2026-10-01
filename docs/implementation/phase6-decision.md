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

## Query latency (p50/p95 ms, adapter-call only, single run)

| Mode | Baseline | Cassandra | YDB | PG |
|---|---|---|---|---|
| Lexical | 0.3 / 14 | 0.2 / 13 | 4.3 / 253 | 95 / 1601 |
| Vector | 3.0 / 147 | 2.6 / 90 | 44 / 3005 | 31 / 1208 |
| Hybrid | 6.0 / 190 | 5.2 / 101 | 2084 / 56082 | 103 / 2761 |

Caveats (load-bearing, read before ranking): mixed `timing_scope`
(single + summed fan-out summed across tenants) is blended into every
cell; PG opens a connection per operation (no pooling — handshake cost
in every query); YDB hybrid tail is extreme skew (p50 2s vs p95 56s —
cold/first-query effects suspected, uninvestigated); single run, no
repeats; adapter-call scope excludes harness overhead by design
(comparator never gates on timing). Latency ranks legs only after a
repeat run with scope-separated percentiles.

## Cost (inputs, not a model yet)

- Write side frozen (034): YDB revision p50/p95 38/58 ms, 77.6 rps at
  20-way concurrency; ~8 rows ≈ 4 KB per 3-chunk document; index
  overhead + replication pricing still open.
- Search side: load rates above are the only measured search-cost input;
  per-query compute cost unmeasured on all legs.
- A cost model needs: managed-service pricing basis per backend,
  replication ×3 storage math, and the missing latency repeats. Not
  attempted here — stated so Phase 6 doesn't assume it exists.

## Operational complexity (qualitative, single-node PoC basis)

- Cassandra: incumbent (production operates it today).
- YDB: newer; restart recovery demonstrated (~30s, no intervention);
  node-loss breadth awaits full 032.
- PG: mature engine, new-to-this-workload; RLS + pgvector ops are
  standard DBA territory; DEFINER audit gates production, not the PoC.
- Headcount/complexity deltas: unassessed on all legs — recorded, not guessed.

## Migration path

- Existing deployment is Cassandra-backed: staying is zero-migration.
- Any move (YDB or PG) needs the Phase-5 migrator path + dual-read
  validation, neither scoped yet. Migration cost is an open input, not
  a footnote — the decision meeting should demand it before selecting
  a non-incumbent outcome.
